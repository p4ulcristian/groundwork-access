(ns groundwork-access.shapes
  "The shapes groundwork-access's messages answer and refuse by, the same on both
   sides: the server's handler answers by them, and the browser reads the
   answer by them (the app puts them in its shapes by name, as every
   message's).")

(def refusals
  "groundwork-access's own reasons, which its steps refuse a message with, each with
   the shape of its data, nil for none (bridge's refusal):
   :groundwork-access/signed-out   nobody is signed in (nobody, a visitor, a token that is wrong, ended
                         or revoked) and the check refused: whatever the record, so it says
                         nothing of whether one exists
   :groundwork-access/not-found    the asker may not reach the record or the scope, nor see it
   :groundwork-access/not-allowed  the asker may see it but not do this, a session flag or a scope's
                         state says no, or the request isn't the app's own (another Origin, a
                         cookie and a token at once)
   :groundwork-access/token-refused  an outside issuer's token failed its check (its keys, issuer,
                         audience, times, algorithm, or a subject no sign-in linked): never
                         nobody, so a client knows to get a new one"
  {:groundwork-access/signed-out    nil
   :groundwork-access/not-found     nil
   :groundwork-access/not-allowed   nil
   :groundwork-access/token-refused nil})

(def my-actions-answer-shape
  "The answer of make-my-actions-handler's handler, \"what may I do?\":
   every scope the person is in with the actions they may do there, and
   an entry of nil kind and id for the actions open to them in every
   scope. By groundwork-shaped-json: scope kinds and actions travel as their text and come
   back keywords.

     [{:scope-kind nil   :scope-id nil      :actions [:profile/edit]}
      {:scope-kind :shop :scope-id #uuid \"…\" :actions [:orders/edit :orders/see]}]"
  [:vector [:map {:closed true}
            [:scope-kind [:maybe :keyword]]
            [:scope-id [:maybe :uuid]]
            [:actions [:vector :keyword]]]])

(defn put-refusals-into-shapes
  "The browser's shapes by name (bridge's), each name's :refusals with
   refusals added, as put-access-into-handlers adds them on the server. A
   name that declares one of them itself throws, :groundwork-access/bad-shapes."
  [shapes-by-name]
  (let [doubled (for [[message-name {name-refusals :refusals}] shapes-by-name
                      reason (keys refusals)
                      :when (contains? name-refusals reason)]
                  (str message-name " declares " reason ", groundwork-access's own: put-refusals-into-shapes adds it"))]
    (when (seq doubled)
      (throw (ex-info (str "The shapes have problems: " (vec doubled)) {:type :groundwork-access/bad-shapes :problems (vec doubled)})))
    (update-vals shapes-by-name #(update % :refusals merge refusals))))
