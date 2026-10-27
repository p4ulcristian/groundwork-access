(ns groundwork-access.cookie
  "The session cookie: 32 random bytes the browser keeps, and their
   fingerprint (SHA-256) the table keeps. Its name is the app's setting
   (:cookie-name)."
  (:require
   [clojure.string :as str])
  (:import
   (java.security MessageDigest SecureRandom)
   (java.util Base64)))

(def ^:private random (SecureRandom.))

(defn make-cookie-value
  "32 random bytes as URL-safe text."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn turn-cookie-value-into-fingerprint
  "The SHA-256 of the cookie's text, as hex."
  [cookie-value]
  (->> (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String cookie-value "UTF-8"))
       (map #(format "%02x" %))
       (apply str)))

;; RFC 6265, 4.2.1: cookie-string = cookie-pair *( ";" SP cookie-pair ),
;; cookie-pair = cookie-name "=" cookie-value, the name a token (RFC 9110's
;; tchar), the value cookie-octets, or cookie-octets in double quotes
(def ^:private cookie-name-pattern #"[!#$%&'*+.^_`|~0-9A-Za-z-]+")
(def ^:private cookie-octets-pattern #"[\x21\x23-\x2B\x2D-\x3A\x3C-\x5B\x5D-\x7E]*")

(defn- read-cookie-pair
  "[name value] of one cookie-pair, its double quotes taken off; nil for a
   pair that isn't one: no =, a name that isn't a token, a value with a
   space, a comma, a backslash or a character outside ASCII in it."
  [pair]
  (let [at (str/index-of pair "=")]
    (when at
      (let [cookie-name (subs pair 0 at)
            value       (subs pair (inc at))
            value       (if (and (<= 2 (count value)) (str/starts-with? value "\"") (str/ends-with? value "\""))
                          (subs value 1 (dec (count value)))
                          value)]
        (when (and (re-matches cookie-name-pattern cookie-name) (re-matches cookie-octets-pattern value))
          [cookie-name value])))))

(defn read-cookie-header
  "A Cookie header's cookies, [[name value] …] in the header's order, by
   RFC 6265's grammar: pairs split by ;, the spaces and tabs around each
   taken off, a quoted value without its quotes; a pair that isn't one is
   left out, the others kept. A name may come twice (a cookie for / and
   one for /app): both are listed, the browser's first first."
  [header]
  (into [] (keep #(read-cookie-pair (str/trim %))) (str/split (or header "") #";")))

(defn find-cookie-value
  "The value of the cookie named cookie-name in a Ring request, or nil: the
   first of that name with a value, as the browser lists the one for the
   longest path first (RFC 6265, 5.4); an empty one is none."
  [request cookie-name]
  (some (fn [[k v]] (when (= k cookie-name) (not-empty v))) (read-cookie-header (get-in request [:headers "cookie"]))))

(defn make-set-cookie-text
  "The Set-Cookie header's text: the cookie for max-age seconds (0 deletes
   it). HttpOnly (no JavaScript reads it), SameSite=Lax (other sites' forms
   don't send it), Secure unless secure? is false (local development)."
  [cookie-name cookie-value max-age-seconds secure?]
  (str/join "; " (cond-> [(str cookie-name "=" cookie-value) "Path=/" "HttpOnly" "SameSite=Lax"
                          (str "Max-Age=" max-age-seconds)]
                   secure? (conj "Secure"))))
