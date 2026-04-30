# Phase 5: Rich Display — Implementation Plan

> Companion to [phase-5-rich-display.md](phase-5-rich-display.md). That doc is the spec; this doc is the build order, gotchas, and additional tests not in the spec.

---

## Implementation Order

1. **Item model + initial state** — add `:args-text`, `:out-text`, `:expanded? false`, `:focused? false` to tool-call items at creation; `:sub-items []` on `eca__spawn_agent` items only; add `:focus-path nil` and `:subagent-chats {}` to `initial-state`. Everything else depends on this.

2. **Protocol handlers: args/output + thinking + hooks** — `toolCallRun` stores `:args-text` (JSON string); `toolCalled` stores `:out-text` (truncate at 8 KB); `reasonStarted/Text/Finished`; `hookActionStarted/Finished`.

3. **Task tool suppression + sub-agent routing** — intercept `{:server "eca" :name "task"}` and drop from `:items`; replace `parentChatId` suppression with routing to parent `:sub-items` via `:subagent-chats`; register sub-agent link on `toolCallRun` when `subagentDetails` is present.

4. **`upsert-tool-call` merge safety** — fix before view work (see gotcha below).

5. **View: collapsed/expanded rendering** — `render-item-lines` gains collapsed/expanded branches per `:tool-call`, `:thinking`, `:hook`; box-drawing arg/output blocks; sub-item indentation; `▸ N steps` suffix; focus indicator (`›`).

6. **Focus/navigation** — Tab/Shift+Tab build render-order path list and advance/reverse; Enter/Space toggles `:expanded?` at `:focus-path`; Escape clears focus; Tab adjusts `:scroll-offset`.

7. All unit tests green, then integration + manual pass.

---

## Gotcha: `upsert-tool-call` Merge Safety

Current `upsert-tool-call` uses `merge`, so every subsequent tool event (e.g. `toolCalled` arriving after the user expands a block) clobbers `:expanded?` back to whatever the incoming map carries. A user-expanded block will snap back to collapsed on the next event.

Fix: protect interactive state fields (`:expanded?`, `:focused?`) from incoming-map overwrite. Either strip those keys from the incoming map before merging, or preserve them explicitly after merge:

```clojure
;; after merge, restore UI state from pre-merge item
(let [prev (get-in state [:items idx])]
  (assoc merged :expanded? (:expanded? prev false)
                :focused?  (:focused? prev false)))
```

This is the single most likely regression source in Phase 5.

---

## Additional Stopping Criteria

Beyond the 17 automated criteria in the spec:

**18.** Task tool suppressed — `toolCallPrepare` / `toolCallRun` with `{:server "eca" :name "task"}` produces no entry in `:items`.

**19.** `upsert-tool-call` preserves `:expanded?` — expanding a tool block stays expanded when a subsequent event (e.g. `toolCalled`) updates the same item.

---

## Additional Tests

Beyond the unit tests in the spec:

```clojure
;; upsert-tool-call preserves :expanded? across subsequent events
(deftest upsert-preserves-expanded-test
  (testing "toolCalled does not reset :expanded? on an already-expanded item"
    (let [base  (assoc (base-state) :mode :chatting
                       :items [{:type :tool-call :id "tc1" :name "read_file"
                                :state :running :expanded? true :focused? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "toolCalled" :id "tc1" :name "read_file"
                                           :server "fs" :arguments {} :error false}}})]
      (is (true? (get-in s [:items 0 :expanded?]))))))

;; :out-text truncated at 8 KB
(deftest out-text-truncation-test
  (testing "toolCalled with large output truncates :out-text"
    (let [big   (apply str (repeat 9000 "x"))
          base  (assoc (base-state) :mode :chatting
                       :items [{:type :tool-call :id "tc1" :name "read_file"
                                :state :running :expanded? false}])
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId "chat1" :role "assistant"
                                 :content {:type "toolCalled" :id "tc1" :name "read_file"
                                           :server "fs" :arguments {} :error false
                                           :output big}}})]
      (is (<= (count (get-in s [:items 0 :out-text])) 8200))
      (is (clojure.string/includes? (get-in s [:items 0 :out-text]) "[truncated]")))))

;; Task tool suppressed
(deftest task-tool-suppressed-test
  (testing "toolCallPrepare for eca/task tool does not add to :items"
    (let [[s _] (handle-eca-notification
                  (assoc (base-state) :mode :chatting)
                  {:method "chat/contentReceived"
                   :params {:chatId "chat1" :role "assistant"
                            :content {:type "toolCallPrepare" :id "tc1"
                                      :name "task" :server "eca" :summary "bg task"}}})]
      (is (empty? (:items s))))))

;; parentChatId fallthrough when no parent registered
(deftest subagent-fallthrough-test
  (testing "contentReceived with parentChatId and no registered parent falls through to main flow"
    (let [base  (assoc (base-state) :mode :chatting :subagent-chats {})
          [s _] (handle-eca-notification
                  base {:method "chat/contentReceived"
                        :params {:chatId       "unregistered-sub"
                                 :parentChatId "chat1"
                                 :role         "assistant"
                                 :content      {:type "text" :text "fallthrough"}}})]
      (is (= 1 (count (:items s)))))))
```
