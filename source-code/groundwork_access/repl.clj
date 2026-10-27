(ns groundwork-access.repl
  "What only a REPL does: ask as a named person. Not groundwork-access's api
   (groundwork-access.api), and never an app's code: an app's code finds the asker
   from the request, by the cookie or the token groundwork-access made. A composer's
   REPL tool (\"ask as this person\") makes its handlers ready with this
   step in place of the find-session step.

     (put-access-into-handlers handlers {… :find-session-step
                                         (groundwork-access.repl/make-step-asking-as
                                          {:person-id anna :visitor-id nil :session-flags #{}})})"
  (:require
   [groundwork-access.askers :as askers]
   [groundwork-access.settings :as settings]))

(defn make-step-asking-as
  "A step in place of the find-session step: the asker given, not a
   request's, put in the context and the message's log as the
   find-session step puts it. No Origin is checked and no session is read.
   The asker, every key required: {:person-id :visitor-id :session-flags}."
  [asker]
  (let [asker (settings/check-asker asker)]
    {:name  :groundwork-access/asking-as
     :enter (fn [context] (askers/put-asker-into-context context asker {:token-id nil :outside-issuer nil}))}))
