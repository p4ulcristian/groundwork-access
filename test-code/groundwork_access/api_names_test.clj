(ns groundwork-access.api-names-test
  "The public names of groundwork-access.api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-access.api]))

(deftest the-api-names
  (is (= '#{;; who is asking
           settings-shape sessions-table outside-subjects-table sign-in! start-visitor-session! sign-out! sign-out-everywhere! delete-ended-sessions!
           find-session-key-of-request make-find-session-step run-as-asker-of-request!
           tokens-table make-token! list-tokens revoke-token! delete-ended-tokens!
           ;; what may they do
           access-tables access-move-from-v0-7 check-scope-kinds does-action-only-read? add-scope!
           add-role! rename-role! set-role-actions! remove-role! list-roles
           add-membership! set-membership-roles! remove-membership!
           list-actions-of-person make-sql-conditions make-access-functions function-files
           put-access-into-handlers put-gates-on-tables find-leaks explain-refusal
           my-actions-answer-shape make-my-actions-handler refusals
           ;; a named system actor
           run-as-system! find-system-leaks}
         (set (keys (ns-publics 'groundwork-access.api))))))
