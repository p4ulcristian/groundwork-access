(ns groundwork-access.outside
  "Outside issuers: a bearer token another issuer signed (WorkOS AuthKit's,
   for an app's /mcp), which groundwork-access checks itself, every time:

     Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6…   a JWT the issuer signed

   The app names each issuer in its settings (settings/outside-issuer-shape).
   A token is checked, in this order, and the first thing wrong refuses it,
   named (:problem):

     :malformed               not three base64url parts, a JSON header and claims
     :unsupported-header      a crit header, an extension nothing here understands
     :unknown-issuer          its iss is no named issuer's :issuer
     :algorithm-not-allowed   its alg is not one of the issuer's :algorithms (never none, never HMAC)
     :no-key-id               its header names no kid
     :keys-unavailable        the issuer's keys could not be fetched, and none are kept
     :unknown-key             the issuer has no key of that kid, fetched again at most every
                              :fetch-at-most-every-seconds
     :key-does-not-fit        the key is not of the alg's kind (RSA, or EC on the alg's curve),
                              or says another alg, or is not for signing
     :bad-signature           the signature is not the key's over the header and claims
     :expired                 no exp, or exp has passed (by more than :clock-skew-seconds)
     :not-yet-valid           nbf is ahead (by more than :clock-skew-seconds)
     :wrong-audience          aud doesn't hold the issuer's :audience
     :no-subject              the :subject-claim is not a text
     :subject-not-linked      no person's sign-in linked that subject (sign-in!'s :outside-subjects)

   Keys: fetched from the issuer's JWKS document (or the one its discovery
   document names, whose issuer must be :issuer), kept :keys-kept-seconds,
   fetched again when a token names a kid they don't have, and fetched at
   most every :fetch-at-most-every-seconds, a failed fetch counted; never
   followed through a redirect. They are kept in this process, by the
   issuer's settings: one fetch serves every pool and every request. Only
   public keys are kept."
  (:require
   [clojure.string :as str]
   [groundwork-access.settings :as settings]
   [groundwork-postgres.api :as postgres]
   [jsonista.core :as json])
  (:import
   (java.math BigInteger)
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
   (java.security AlgorithmParameters KeyFactory PublicKey Signature)
   (java.security.interfaces RSAPublicKey)
   (java.security.spec ECGenParameterSpec ECParameterSpec ECPoint ECPublicKeySpec RSAPublicKeySpec)
   (java.time Duration)
   (java.util Base64)
   (java.util.concurrent ConcurrentHashMap)
   (java.util.function Function)))

(def ^:private algorithms
  "What each supported alg needs of its key, and how Java verifies it. An
   ECDSA signature in a JWT is r and s side by side (P1363), each the
   curve's size."
  {"RS256" {:kty "RSA" :java "SHA256withRSA"}
   "RS384" {:kty "RSA" :java "SHA384withRSA"}
   "RS512" {:kty "RSA" :java "SHA512withRSA"}
   "ES256" {:kty "EC" :crv "P-256" :java "SHA256withECDSAinP1363Format" :signature-length 64}
   "ES384" {:kty "EC" :crv "P-384" :java "SHA384withECDSAinP1363Format" :signature-length 96}
   "ES512" {:kty "EC" :crv "P-521" :java "SHA512withECDSAinP1363Format" :signature-length 132}})

(def ^:private curves {"P-256" "secp256r1" "P-384" "secp384r1" "P-521" "secp521r1"})

(def ^:private smallest-rsa-bits
  "An RSA key smaller than this is no key: RFC 7518 asks 2048 bits of RS256."
  2048)

;; -----------------------------------------------------------------------------
;; Reading a token

(defn- decode-part
  "The bytes of a base64url part (none for an empty one: an unsigned
   token's signature, refused by its alg), or nil when it isn't one."
  ^bytes [^String part]
  (when (re-matches #"[A-Za-z0-9_-]*" part)
    (try (.decode (Base64/getUrlDecoder) part) (catch IllegalArgumentException _ nil))))

(defn- read-json-object
  "A JSON object's map (text keys), or nil for anything else."
  [^bytes bytes]
  (when bytes
    (let [value (try (json/read-value (String. bytes "UTF-8")) (catch Exception _ nil))]
      (when (map? value) value))))

(defn read-token
  "{:header :claims :signing-input :signature} of a JWT's text, or nil when
   it isn't one: three base64url parts, the first two JSON objects."
  [text]
  (let [parts (str/split text #"\." -1)]
    (when (= 3 (count parts))
      (let [[header-part claims-part signature-part] parts
            header    (read-json-object (decode-part header-part))
            claims    (read-json-object (decode-part claims-part))
            signature (decode-part signature-part)]
        (when (and header claims signature)
          {:header        header
           :claims        claims
           :signing-input (str header-part "." claims-part)
           :signature     signature})))))

;; -----------------------------------------------------------------------------
;; Keys

(defn- turn-part-into-number [part]
  (some->> part str (decode-part) (BigInteger. 1)))

(defn- make-rsa-key [{:strs [n e]}]
  (let [modulus  (turn-part-into-number n)
        exponent (turn-part-into-number e)]
    (when (and modulus exponent)
      (let [^RSAPublicKey public-key (.generatePublic (KeyFactory/getInstance "RSA") (RSAPublicKeySpec. modulus exponent))]
        (when (<= smallest-rsa-bits (.bitLength (.getModulus public-key)))
          public-key)))))

(defn- make-ec-key [{:strs [crv x y]}]
  (let [curve (get curves crv)
        x     (turn-part-into-number x)
        y     (turn-part-into-number y)]
    (when (and curve x y)
      (let [parameters (doto (AlgorithmParameters/getInstance "EC") (.init (ECGenParameterSpec. curve)))]
        (.generatePublic (KeyFactory/getInstance "EC")
                         (ECPublicKeySpec. (ECPoint. x y) (.getParameterSpec parameters ECParameterSpec)))))))

(defn turn-jwk-into-key
  "{:public-key :kty :crv :alg :use} of one key of a JWKS document, or nil
   for one that can't be one of ours: no kid, not RSA or EC, an RSA key
   under 2048 bits, a curve not P-256, P-384 or P-521, one for encryption,
   or numbers that aren't."
  [{:strs [kid kty crv alg use] :as jwk}]
  (when (and (string? kid) (not= "enc" use))
    (when-let [public-key (try (case kty
                                 "RSA" (make-rsa-key jwk)
                                 "EC"  (make-ec-key jwk)
                                 nil)
                               (catch Exception _ nil))]
      {:public-key public-key :kty kty :crv crv :alg alg :use use})))

(defn- fetch-json
  "The JSON object at an address, or throws: connected within
   :connect-timeout-ms, answered within :answer-timeout-ms, 200, never
   following a redirect."
  [{:keys [connect-timeout-ms answer-timeout-ms]} address]
  (with-open [client (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                         (.followRedirects HttpClient$Redirect/NEVER)
                         (.build))]
    (let [request  (-> (HttpRequest/newBuilder (URI/create address))
                       (.timeout (Duration/ofMillis answer-timeout-ms))
                       (.header "accept" "application/json")
                       (.GET)
                       (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (when-not (= 200 (.statusCode response))
        (throw (ex-info (str address " answered " (.statusCode response)) {:address address :status (.statusCode response)})))
      (or (read-json-object (.getBytes ^String (.body response) "UTF-8"))
          (throw (ex-info (str address " answered no JSON object") {:address address}))))))

(defn- is-address-allowed? [address]
  (boolean (and (string? address) (re-matches settings/address-pattern address))))

(defn- find-jwks-address
  "Where the issuer's keys are: its JWKS address, or the jwks_uri of its
   discovery document, whose issuer must be the issuer's."
  [{:keys [issuer] [where address] :keys-at :as outside-issuer}]
  (if (= :jwks where)
    address
    (let [{document-issuer "issuer" jwks-uri "jwks_uri"} (fetch-json outside-issuer address)]
      (when-not (= issuer document-issuer)
        (throw (ex-info (str "The discovery document's issuer is " (pr-str document-issuer) ", not " issuer) {:address address})))
      (when-not (is-address-allowed? jwks-uri)
        (throw (ex-info (str "The discovery document's jwks_uri is " (pr-str jwks-uri) ", not an https:// address") {:address address})))
      jwks-uri)))

(defn fetch-keys
  "{kid key} of the issuer's JWKS document, the keys it can't use left out."
  [outside-issuer]
  (let [{:strs [keys]} (fetch-json outside-issuer (find-jwks-address outside-issuer))]
    (when-not (sequential? keys)
      (throw (ex-info "The JWKS document has no keys list" {})))
    (into {} (keep (fn [jwk] (when-let [k (and (map? jwk) (turn-jwk-into-key jwk))] [(get jwk "kid") k]))) keys)))

(defonce ^:private kept
  ;; the issuer's settings -> {:keys {kid key} :fetched-at ms :tried-at ms}
  (atom {}))

(defonce ^:private fetch-locks
  ;; the issuer's settings -> what one fetch of its keys at a time holds
  (ConcurrentHashMap.))

(defn- find-lock [outside-issuer]
  (.computeIfAbsent ^ConcurrentHashMap fetch-locks outside-issuer
                    (reify Function (apply [_ _] (Object.)))))

(defn- are-fresh? [{:keys [fetched-at]} {:keys [keys-kept-seconds]} now]
  (and fetched-at (< (- now fetched-at) (* 1000 keys-kept-seconds))))

(defn- may-fetch? [{:keys [tried-at]} {:keys [fetch-at-most-every-seconds]} now]
  (or (nil? tried-at) (<= (* 1000 fetch-at-most-every-seconds) (- now tried-at))))

(defn- fetch-and-keep! [outside-issuer now]
  (swap! kept update outside-issuer assoc :tried-at now)
  (try
    (let [found (fetch-keys outside-issuer)]
      (swap! kept assoc outside-issuer {:keys found :fetched-at now :tried-at now})
      nil)
    (catch Exception e
      (.getMessage e))))

(defn find-key
  "{:key …} of the issuer's key by its kid, or {:problem :unknown-key
   or :keys-unavailable :because text}: from the kept keys while fresh;
   fetched again when they aren't, or don't have the kid, at most every
   :fetch-at-most-every-seconds; one fetch at a time per issuer."
  [outside-issuer kid]
  (let [found-now (fn [now]
                    (let [entry (get @kept outside-issuer)]
                      (when (are-fresh? entry outside-issuer now)
                        (get-in entry [:keys kid]))))
        now       (System/currentTimeMillis)
        lock      (find-lock outside-issuer)]   ; the issuer's one, shared by every request
    (if-let [k (found-now now)]
      {:key k}
      (locking lock
        (let [now (System/currentTimeMillis)]
          (if-let [k (found-now now)]              ; fetched by another request meanwhile
            {:key k}
            (let [failure (when (may-fetch? (get @kept outside-issuer) outside-issuer now)
                            (fetch-and-keep! outside-issuer now))
                  entry   (get @kept outside-issuer)]
              (cond
                (found-now now)                       {:key (found-now now)}
                (are-fresh? entry outside-issuer now) {:problem :unknown-key :because (str "the issuer has no key " (pr-str kid))}
                :else                                 {:problem :keys-unavailable
                                                       :because (str "the issuer's keys could not be fetched"
                                                                     (when failure (str ": " failure)))}))))))))

;; -----------------------------------------------------------------------------
;; The check

(defn- does-key-fit? [{:keys [kty crv alg use]} token-alg]
  (let [{need-kty :kty need-crv :crv} (get algorithms token-alg)]
    (and (= need-kty kty)
         (or (nil? need-crv) (= need-crv crv))
         (or (nil? alg) (= alg token-alg))
         (or (nil? use) (= "sig" use)))))

(defn- is-signature-right? [^PublicKey public-key token-alg ^String signing-input ^bytes signature]
  (let [{:keys [java signature-length]} (get algorithms token-alg)]
    (and (or (nil? signature-length) (= signature-length (alength signature)))
         (try
           (let [verifier (Signature/getInstance java)]
             (.initVerify verifier public-key)
             (.update verifier (.getBytes signing-input "US-ASCII"))
             (.verify verifier signature))
           (catch Exception _ false)))))

(defn- has-audience? [aud audience]
  (cond (string? aud)     (= aud audience)
        (sequential? aud) (boolean (some #(= audience %) aud))
        :else             false))

(defn- check-times
  "nil, or the problem with exp and nbf, now and the skew in seconds."
  [{:strs [exp nbf]} now-seconds skew]
  (cond (not (number? exp))                              :expired
        (<= (+ exp skew) now-seconds)                    :expired
        (and (some? nbf) (not (number? nbf)))            :not-yet-valid
        (and (number? nbf) (< (+ now-seconds skew) nbf)) :not-yet-valid
        :else                                            nil))

(defn- refuse [issuer-name problem because]
  {:problem problem :issuer-name issuer-name :because because})

(defn check-token
  "{:issuer-name :subject} of an outside issuer's token, or {:problem
   :issuer-name (nil when no issuer is known) :because}: the token read,
   its issuer found by iss, then its alg, key, signature, times, audience
   and subject checked, in that order."
  [text outside-issuers]
  (let [{:keys [header claims signing-input signature] :as token} (read-token text)
        outside-issuer (when token (some #(when (= (:issuer %) (get claims "iss")) %) outside-issuers))
        issuer-name    (:name outside-issuer)
        {allowed :algorithms :keys [subject-claim audience clock-skew-seconds]} outside-issuer
        token-alg      (get header "alg")
        kid            (get header "kid")]
    (cond
      (nil? token)
      (refuse nil :malformed "it is not a JWT: three base64url parts, a JSON header and claims")

      (contains? header "crit")
      (refuse nil :unsupported-header "its header has crit, an extension nothing here understands")

      (nil? outside-issuer)
      (refuse nil :unknown-issuer (str "its iss " (pr-str (get claims "iss")) " is no outside issuer the settings name"))

      (not (contains? allowed token-alg))
      (refuse issuer-name :algorithm-not-allowed (str "its alg " (pr-str token-alg) " is not one of " (vec (sort allowed))))

      (not (string? kid))
      (refuse issuer-name :no-key-id "its header names no kid")

      :else
      (let [{found :key problem :problem because :because} (find-key outside-issuer kid)
            subject (get claims subject-claim)]
        (cond
          problem
          (refuse issuer-name problem because)

          (not (does-key-fit? found token-alg))
          (refuse issuer-name :key-does-not-fit (str "the key " (pr-str kid) " is not one " token-alg " signs with"))

          (not (is-signature-right? (:public-key found) token-alg signing-input signature))
          (refuse issuer-name :bad-signature "its signature is not the issuer's")

          :else
          (if-let [time-problem (check-times claims (/ (System/currentTimeMillis) 1000.0) clock-skew-seconds)]
            (refuse issuer-name time-problem (if (= :expired time-problem) "it has no exp, or exp has passed" "its nbf is ahead"))
            (cond
              (not (has-audience? (get claims "aud") audience))
              (refuse issuer-name :wrong-audience (str "its aud " (pr-str (get claims "aud")) " doesn't hold " (pr-str audience)))

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
