(ns groundwork-access.cookie-test
  "The session cookie read from the Cookie header, by RFC 6265's grammar:
   pure, no database."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.api :as access]
   [groundwork-access.cookie :as cookie]))

(defn- value-in [header]
  (cookie/find-cookie-value {:headers {"cookie" header}} "app_session"))

(deftest the-session-cookie-is-read-by-rfc-6265
  (testing "a pair among others, with or without the space after ;"
    (is (= "abc" (value-in "app_session=abc")))
    (is (= "abc" (value-in "lang=de; app_session=abc; theme=dark")))
    (is (= "abc" (value-in "lang=de;app_session=abc")))
    (is (= "abc" (value-in "  lang=de ;\tapp_session=abc  ")) "spaces and tabs around a pair"))
  (testing "a quoted value, without its quotes"
    (is (= "abc" (value-in "app_session=\"abc\"")))
    (is (= "" (second (first (cookie/read-cookie-header "app_session=\"\"")))) "quotes around nothing: empty"))
  (testing "a name twice: the first, the browser's cookie for the longest path"
    (is (= "first" (value-in "app_session=first; app_session=second")))
    (is (= "second" (value-in "app_session=; app_session=second")) "an empty one is none"))
  (testing "bad pairs are left out, the good ones kept"
    (is (= "abc" (value-in "no-equals; =nameless; bad name=1; app_session=abc")))
    (is (nil? (value-in "app_session=ab c")) "a space inside a value")
    (is (nil? (value-in "app_session=a\\b")) "a backslash")
    (is (nil? (value-in "app_session=a,b")) "a comma")
    (is (nil? (value-in "app_session=\"abc")) "an unclosed quote")
    (is (nil? (value-in "app_session=é")) "outside ASCII")
    (is (nil? (value-in "app_session =abc")) "a space before =: another name")
    (is (nil? (value-in "app_session=")) "empty"))
  (testing "the name exactly: another cookie whose name ends or starts with it is not it"
    (is (nil? (value-in "my_app_session=abc; app_session_old=def"))))
  (testing "no header, an empty one"
    (is (nil? (cookie/find-cookie-value {:headers {}} "app_session")))
    (is (nil? (value-in ""))))
  (testing "every pair in the header's order; = inside a value is the value's"
    (is (= [["a" "1"] ["b" "x=y"] ["a" "2"]] (cookie/read-cookie-header "a=1; b=x=y; a=2")))))

(deftest the-rate-limit-key-reads-the-same-cookie
  (let [settings {:cookie-name "app_session"}
        key-of   #(access/find-session-key-of-request {:headers {"cookie" %}} settings)]
    (is (some? (key-of "app_session=abc")))
    (is (= (key-of "app_session=abc") (key-of "lang=de; app_session=\"abc\"")))
    (is (nil? (key-of "app_session=ab c; other=1")))))
