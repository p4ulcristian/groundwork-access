(ns groundwork-access.outside
  "Outside issuers: a bearer token another issuer signed (WorkOS AuthKit's,
   for an app's /mcp), which groundwork-access checks every time:

     Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6…   a JWT the issuer signed

   The JWT is read, its key set fetched and kept, its key chosen and its
   signature checked by nimbus-jose-jwt (com.nimbusds/nimbus-jose-jwt);
   what is accepted, and in which order, is groundwork-access's policy on
   top of it. The app names each issuer in its settings
   (settings/outside-issuer-shape). A token is checked, in this order, and
   the first thing wrong refuses it, named (:problem):

     :malformed               not a signed JWT: three base64url parts, a JSON header and claims
     :unsupported-header      a crit header, an extension nothing here understands
     :unknown-issuer          its iss is no named issuer's :issuer
     :algorithm-not-allowed   its alg is not one of the issuer's :algorithms (never none, never HMAC)
     :no-key-id               its header names no kid
     :keys-unavailable        the issuer's keys could not be fetched, and none are kept
     :unknown-key             the issuer has no key of that kid, fetched again at most every
                              :fetch-at-most-every-seconds
     :key-does-not-fit        the key is not of the alg's kind (RSA of 2048 bits or more, or EC
                              on the alg's curve), or says another alg, or is not for signing
     :bad-signature           the signature is not the key's over the header and claims
     :expired                 no exp, or exp has passed (by more than :clock-skew-seconds)
     :not-yet-valid           nbf is ahead (by more than :clock-skew-seconds)
     :wrong-audience          aud doesn't hold the issuer's :audience
     :no-subject              the :subject-claim is not a text
     :subject-not-linked      no person's sign-in linked that subject (sign-in!'s :outside-subjects)

   Keys: nimbus's key set of the issuer's JWKS document (or the one its
   discovery document names, whose issuer must be :issuer), kept by
   nimbus's cache :keys-kept-seconds and fetched again by it when a token
   names a kid they don't have; fetched at most every
   :fetch-at-most-every-seconds, a failed fetch counted; one fetch at a
   time per issuer; never followed through a redirect. Each issuer's key
   set is kept in this process, by the issuer's settings: one fetch serves
   every pool and every request."
  (:require
   [groundwork-access.settings :as settings]
   [groundwork-postgres.api :as postgres]
   [jsonista.core :as json])
  (:import
   (com.nimbusds.jose Header JWSAlgorithm$Family JWSHeader KeySourceException)
   (com.nimbusds.jose.crypto ECDSAVerifier RSASSAVerifier)
   (com.nimbusds.jose.jwk Curve ECKey JWK JWKMatcher JWKMatcher$Builder JWKSelector RSAKey)
   (com.nimbusds.jose.jwk.source CachingJWKSetSource JWKSetBasedJWKSource JWKSetSource JWKSource
                                 RateLimitReachedException URLBasedJWKSetSource)
   (com.nimbusds.jose.util DateUtils Resource ResourceRetriever)
   (com.nimbusds.jwt JWT JWTClaimsSet JWTParser SignedJWT)
   (java.io IOException)
   (java.net URI URL)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse HttpResponse$BodyHandlers)
   (java.text ParseException)
   (java.time Duration)
   (java.util Date)
   (java.util.concurrent ConcurrentHashMap)
   (java.util.function Function)))

(def ^:private smallest-rsa-bits
  "An RSA key smaller than this doesn't fit: RFC 7518 asks 2048 bits of RS256."
  2048)

;; -----------------------------------------------------------------------------
;; Reading a token

(defn read-token
  "{:header :claims :jwt} of a JWT's text, by nimbus's JWTParser, or nil
   when it isn't one: three base64url parts, a JSON header and JSON
   claims (an encrypted JWT's claims aren't readable here: nil). An
   unsigned one (alg none) is read, and refused by its alg."
  [^String text]
  (try
    (let [^JWT jwt (JWTParser/parse text)
          claims   (.getJWTClaimsSet jwt)]
      (when claims
        {:header (.getHeader jwt) :claims claims :jwt jwt}))
    (catch ParseException _ nil)))

;; -----------------------------------------------------------------------------
;; Keys

(defn- make-retriever
  "nimbus's ResourceRetriever on the JDK's HttpClient, by the issuer's
   settings: connected within :connect-timeout-ms, answered within
   :answer-timeout-ms (the whole answer), 200 only, never following a
   redirect."
  ^ResourceRetriever [{:keys [connect-timeout-ms answer-timeout-ms]}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))]
    (reify ResourceRetriever
      (retrieveResource [_ url]
        (let [request  (-> (HttpRequest/newBuilder (.toURI ^URL url))
                           (.timeout (Duration/ofMillis answer-timeout-ms))
                           (.header "accept" "application/json")
                           (.GET)
                           (.build))
              ^HttpResponse response (try (.send client request (HttpResponse$BodyHandlers/ofString))
                            (catch InterruptedException e
                              (.interrupt (Thread/currentThread))
                              (throw (IOException. "interrupted" e))))]
          (when-not (= 200 (.statusCode response))
            (throw (IOException. (str url " answered " (.statusCode response)))))
          (Resource. ^String (.body response) ^String (.orElse (.firstValue (.headers response) "content-type") nil)))))))

(defn- is-address-allowed? [address]
  (boolean (and (string? address) (re-matches settings/address-pattern address))))

(defn- find-jwks-address
  "Where the issuer's keys are: its JWKS address, or the jwks_uri of its
   discovery document, whose issuer must be the issuer's."
  [{:keys [issuer] [where address] :keys-at} ^ResourceRetriever retriever]
  (if (= :jwks where)
    address
    (let [document (try (json/read-value (.getContent (.retrieveResource retriever (.toURL (URI. ^String address)))))
                        (catch IOException e (throw e))
                        (catch Exception _ nil))
          {document-issuer "issuer" jwks-uri "jwks_uri"} (when (map? document) document)]
      (when-not (map? document)
        (throw (IOException. (str address " answered no JSON object"))))
      (when-not (= issuer document-issuer)
        (throw (IOException. (str "the discovery document's issuer is " (pr-str document-issuer) ", not " issuer))))
      (when-not (is-address-allowed? jwks-uri)
        (throw (IOException. (str "the discovery document's jwks_uri is " (pr-str jwks-uri) ", not an https:// address"))))
      jwks-uri)))

(defn- make-fetching-source
  "The source under nimbus's cache: the issuer's JWKS document, parsed by
   nimbus (URLBasedJWKSetSource), its address found first when the
   issuer's keys are at a discovery document. Fetches at most every
   :fetch-at-most-every-seconds, a failed fetch counted (nimbus's own
   rate limit allows two a period); between, it refuses with nimbus's
   RateLimitReachedException. Notes when it last fetched, in fetches."
  ^JWKSetSource [outside-issuer fetches]
  (let [{:keys [fetch-at-most-every-seconds]} outside-issuer
        retriever (make-retriever outside-issuer)]
    (reify JWKSetSource
      (getJWKSet [_ evaluator current-time context]
        (let [may-fetch? (volatile! false)]
          (swap! fetches (fn [{:keys [tried-at] :as noted}]
                           (vreset! may-fetch? (or (nil? tried-at) (<= (* 1000 fetch-at-most-every-seconds) (- current-time tried-at))))
                           (cond-> noted @may-fetch? (assoc :tried-at current-time))))
          (when-not @may-fetch?
            (throw (RateLimitReachedException.)))
          (let [address (try (.toURL (URI. (find-jwks-address outside-issuer retriever)))
                             (catch Exception e
                               (throw (KeySourceException. (str "Couldn't find the JWKS document: " (.getMessage e)) e))))
                found   (.getJWKSet (URLBasedJWKSetSource. address retriever) evaluator current-time context)]
            (swap! fetches assoc :fetched-at current-time)
            found)))
      (close [_]))))

(defn- make-key-set
  "{:source :fetches} of an issuer: nimbus's JWKSource over its cache
   (CachingJWKSetSource: keys kept :keys-kept-seconds, one refresh at a
   time, the others waiting at most as long as a fetch may take) over the
   fetching source. No refresh ahead, no retries, no outage tolerance:
   stale keys are never used."
  [{:keys [keys-kept-seconds connect-timeout-ms answer-timeout-ms] [where] :keys-at :as outside-issuer}]
  (let [fetches        (atom {:tried-at nil :fetched-at nil})
        fetch-ms       (* (if (= :discovery where) 2 1) (+ connect-timeout-ms answer-timeout-ms))
        cached         (CachingJWKSetSource. (make-fetching-source outside-issuer fetches) (* 1000 keys-kept-seconds) fetch-ms nil)]
    {:source (JWKSetBasedJWKSource. cached) :fetches fetches}))

(defonce ^:private key-sets
  ;; the issuer's settings -> {:source JWKSource :fetches atom}
  (ConcurrentHashMap.))

(defn- find-key-set [outside-issuer]
  (.computeIfAbsent ^ConcurrentHashMap key-sets outside-issuer
                    (reify Function (apply [_ issuer] (make-key-set issuer)))))

(defn- are-fresh? [{:keys [fetched-at]} {:keys [keys-kept-seconds]} now]
  (boolean (and fetched-at (< (- now fetched-at) (* 1000 keys-kept-seconds)))))

(defn find-key
  "{:key jwk} of the issuer's key by its kid, or {:problem :unknown-key
   or :keys-unavailable :because text}: nimbus's key set, kept while fresh,
   fetched again when it isn't, or doesn't have the kid, at most every
   :fetch-at-most-every-seconds."
  [outside-issuer kid]
  (let [{:keys [^JWKSource source fetches]} (find-key-set outside-issuer)
        selector (JWKSelector. (-> (JWKMatcher$Builder.) (.keyID ^String kid) (.build)))
        found    (try (vec (.get source selector nil))
                      (catch Exception e e))]
    (cond
      (and (vector? found) (seq found))
      {:key (first found)}

      (or (vector? found) (are-fresh? @fetches outside-issuer (System/currentTimeMillis)))
      {:problem :unknown-key :because (str "the issuer has no key " (pr-str kid))}

      :else
      {:problem :keys-unavailable
       :because (str "the issuer's keys could not be fetched"
                     (when-not (instance? RateLimitReachedException found)
                       (str ": " (.getMessage ^Exception found))))})))

;; -----------------------------------------------------------------------------
;; The check

(defn- make-fit-matcher
  "nimbus's matcher of a key the header's alg may be checked by: of the
   alg's kind, its kid, for signing or unsaid, its alg or unsaid; an EC key
   on the alg's curve, an RSA key of 2048 bits or more."
  ^JWKMatcher [^JWSHeader header]
  (let [alg (.getAlgorithm header)]
    (cond-> (JWKMatcher$Builder. (JWKMatcher/forJWSHeader header))
      (.contains JWSAlgorithm$Family/EC alg)  (.curves ^java.util.Set (Curve/forJWSAlgorithm alg))
      (.contains JWSAlgorithm$Family/RSA alg) (.minKeySize (int smallest-rsa-bits))
      true                                    (.build))))

(defn- is-signature-right? [^SignedJWT jwt ^JWK jwk]
  (try
    (.verify jwt (if (instance? RSAKey jwk)
                   (RSASSAVerifier. ^RSAKey jwk)
                   (ECDSAVerifier. ^ECKey jwk)))
    (catch Exception _ false)))

(defn- check-times
  "nil, or the problem with exp and nbf, by nimbus's DateUtils and the
   skew in seconds."
  [^JWTClaimsSet claims skew]
  (let [now (Date.)
        exp (.getExpirationTime claims)
        nbf (.getNotBeforeTime claims)]
    (cond (nil? exp)                                       :expired
          (not (DateUtils/isAfter exp now skew))           :expired
          (and nbf (not (DateUtils/isBefore nbf now skew))) :not-yet-valid
          :else                                            nil)))

(defn- refuse [issuer-name problem because]
  {:problem problem :issuer-name issuer-name :because because})

(defn check-token
  "{:issuer-name :subject} of an outside issuer's token, or {:problem
   :issuer-name (nil when no issuer is known) :because}: the token read,
   its issuer found by iss, then its alg, key, signature, times, audience
   and subject checked, in that order."
  [text outside-issuers]
  (let [{:keys [header ^JWTClaimsSet claims jwt] :as token} (read-token text)
        outside-issuer (when token (some #(when (= (:issuer %) (.getIssuer claims)) %) outside-issuers))
        issuer-name    (:name outside-issuer)
        {allowed :algorithms :keys [subject-claim audience clock-skew-seconds]} outside-issuer
        token-alg      (when token (some-> (.getAlgorithm ^Header header) (.getName)))]
    (cond
      (nil? token)
      (refuse nil :malformed "it is not a signed JWT: three base64url parts, a JSON header and claims")

      (contains? (set (.getIncludedParams ^Header header)) "crit")
      (refuse nil :unsupported-header "its header has crit, an extension nothing here understands")

      (nil? outside-issuer)
      (refuse nil :unknown-issuer (str "its iss " (pr-str (.getIssuer claims)) " is no outside issuer the settings name"))

      (not (and (instance? SignedJWT jwt) (contains? allowed token-alg)))
      (refuse issuer-name :algorithm-not-allowed (str "its alg " (pr-str token-alg) " is not one of " (vec (sort allowed))))

      (not (string? (.getKeyID ^JWSHeader header)))
      (refuse issuer-name :no-key-id "its header names no kid")

      :else
      (let [kid     (.getKeyID ^JWSHeader header)
            {found :key problem :problem because :because} (find-key outside-issuer kid)
            subject (.getClaim claims ^String subject-claim)]
        (cond
          problem
          (refuse issuer-name problem because)

          (not (.matches (make-fit-matcher header) found))
          (refuse issuer-name :key-does-not-fit (str "the key " (pr-str kid) " is not one " token-alg " signs with"))

          (not (is-signature-right? jwt found))
          (refuse issuer-name :bad-signature "its signature is not the issuer's")

          :else
          (if-let [time-problem (check-times claims clock-skew-seconds)]
            (refuse issuer-name time-problem (if (= :expired time-problem) "it has no exp, or exp has passed" "its nbf is ahead"))
            (cond
              (not (some #(= audience %) (.getAudience claims)))
              (refuse issuer-name :wrong-audience (str "its aud " (pr-str (vec (.getAudience claims))) " doesn't hold " (pr-str audience)))

              (not (and (string? subject) (seq subject)))
              (refuse issuer-name :no-subject (str "its " subject-claim " is not a text"))

              :else
              {:issuer-name issuer-name :subject subject})))))))

;; -----------------------------------------------------------------------------
;; Subjects linked to people

(defn- make-query [file-name]
  (postgres/make-query-from-sql-file (str "groundwork_access/" file-name)))

(def ^:private insert-outside-subject             (make-query "insert_outside_subject.sql"))
(def ^:private find-outside-subject               (make-query "find_outside_subject.sql"))
(def ^:private delete-outside-subjects-of-person  (make-query "delete_outside_subjects_of_person.sql"))

(defn link-subjects!
  "The person's subject at each issuer, {:workos \"user_01…\"}: a token of
   that issuer with that subject is the person's from now on, whoever's it
   was before."
  [transaction person-id outside-subjects]
  (doseq [[issuer-name subject] outside-subjects]
    (postgres/run-statement! transaction insert-outside-subject {:outside-subject/issuer    (name issuer-name)
                                                                 :outside-subject/subject   subject
                                                                 :outside-subject/person-id person-id})))

(defn find-linked-person
  "The person a subject at an issuer is linked to, or nil."
  [pool-or-transaction issuer-name subject]
  (:outside-subject/person-id (postgres/find-one-row pool-or-transaction find-outside-subject {:outside-subject/issuer  (name issuer-name)
                                                                                               :outside-subject/subject subject})))

(defn delete-subjects-of-person!
  "Unlinks every subject of the person, at every issuer; how many."
  [transaction person-id]
  (postgres/run-statement! transaction delete-outside-subjects-of-person {:outside-subject/person-id person-id}))
