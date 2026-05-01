(ns eca-bb.chat
  "Chat-domain state helpers and ECA content handlers.
  No back-references to eca-bb.state — features depend on leaf utils only."
  (:require [cheshire.core :as json]
            [charm.components.text-input :as ti]
            [eca-bb.protocol :as protocol]
            [eca-bb.sessions :as sessions]
            [eca-bb.view :as view]))

;; --- State helpers ---

(defn flush-current-text [state]
  (if (seq (:current-text state))
    (-> state
        (update :items conj {:type :assistant-text :text (:current-text state)})
        (assoc :current-text ""))
    state))

(defn upsert-tool-call [state tool-call]
  (let [id     (:id tool-call)
        merged (merge (get-in state [:tool-calls id]) tool-call)]
    (-> state
        (assoc-in [:tool-calls id] merged)
        (update :items
                (fn [items]
                  (if (some #(= id (:id %)) items)
                    (mapv (fn [item]
                            (if (= id (:id item))
                              ;; Protect interactive state from being clobbered by incoming events
                              (let [protected (select-keys item [:expanded? :focused? :sub-items])]
                                (merge item {:type :tool-call}
                                       (dissoc tool-call :expanded? :focused? :sub-items)
                                       protected))
                              item))
                          items)
                    (let [spawn? (= "eca__spawn_agent" (:name merged))
                          base   (cond-> (assoc merged :type :tool-call
                                                       :expanded? false :focused? false)
                                   spawn? (assoc :sub-items []))]
                      (conj items base))))))))

(defn content->item [params]
  (let [content (:content params)]
    (case (:type content)
      "text"
      {:type :assistant-text :text (:text content)}

      ("toolCallPrepare" "toolCallRunning" "toolCallRun" "toolCalled" "toolCallRejected")
      {:type :tool-call :id (:id content) :name (:name content)
       :server (:server content) :summary (:summary content)
       :state (case (:type content)
                "toolCallPrepare"  :preparing
                "toolCallRun"      :run
                "toolCallRunning"  :running
                "toolCalled"       :called
                "toolCallRejected" :rejected)
       :expanded? false :focused? false}

      "reasonStarted"
      {:type :thinking :id (:id content) :text "" :status :thinking
       :expanded? false :focused? false}

      nil)))

(defn focusable-paths [items]
  (into []
    (mapcat
      (fn [[i item]]
        (when (#{:tool-call :thinking :hook} (:type item))
          (cons [i]
            (when (:expanded? item)
              (keep-indexed
                (fn [j sub]
                  (when (#{:tool-call :thinking :hook} (:type sub))
                    [i j]))
                (or (:sub-items item) []))))))
      (map-indexed vector items))))

(defn sync-focus [state]
  (let [path   (:focus-path state)
        items  (mapv (fn [item]
                       (cond-> (assoc item :focused? false)
                         (:sub-items item)
                         (update :sub-items #(mapv (fn [s] (assoc s :focused? false)) %))))
                     (:items state))
        items' (if path
                 (let [[i j] path]
                   (if j
                     (assoc-in items [i :sub-items j :focused?] true)
                     (assoc-in items [i :focused?] true)))
                 items)]
    (assoc state :items items')))

(defn register-subagent [state tool-id subagent-chat-id]
  (if-let [idx (first (keep-indexed #(when (= tool-id (:id %2)) %1) (:items state)))]
    (assoc-in state [:subagent-chats subagent-chat-id] idx)
    state))

;; --- Outbound prompt ---

(defn send-chat-prompt! [srv chat-id text opts]
  (protocol/chat-prompt!
    srv
    (cond-> {:message text}
      chat-id       (assoc :chat-id chat-id)
      (:model opts) (assoc :model (:model opts))
      (:agent opts) (assoc :agent (:agent opts)))
    (fn [result]
      (when-let [new-id (:chat-id result)]
        (sessions/save-chat-id! (:workspace opts) new-id))
      (.put (:queue srv)
            {:type    :eca-prompt-response
             :chat-id (:chat-id result)
             :model   (:model result)
             :status  (:status result)}))))

;; --- ECA content handler ---

(defn handle-content [state params]
  (let [content (:content params)]
    (case (:type content)
      "text"
      (-> state
          (update :current-text str (:text content))
          view/rebuild-lines)

      "progress"
      (if (= "finished" (:state content))
        (-> state
            flush-current-text
            (assoc :mode :ready :echo-pending false)
            (update :input ti/focus)
            view/rebuild-lines)
        state)

      "toolCallPrepare"
      (if (and (= "eca" (:server content)) (= "task" (:name content)))
        state
        (-> state
            flush-current-text
            (upsert-tool-call {:id             (:id content)
                               :name           (:name content)
                               :server         (:server content)
                               :summary        (:summary content)
                               :arguments-text (:argumentsText content)
                               :state          :preparing})
            view/rebuild-lines))

      "toolCallRun"
      (let [{:keys [id name server summary arguments manualApproval subagentDetails]} content]
        (if (and (= "eca" server) (= "task" name))
          state
          (let [trust?    (or (:trust state)
                              (contains? (:session-trusted-tools state) name))
                args-text (when arguments
                            (try (json/generate-string arguments)
                                 (catch Exception _ (pr-str arguments))))
                tool      {:id id :name name :server server
                           :summary summary :arguments arguments
                           :args-text args-text :state :run}]
            (if (and manualApproval (not trust?))
              (let [s' (-> state
                           (upsert-tool-call tool)
                           (assoc :mode :approving
                                  :pending-approval {:chat-id (:chat-id state) :tool-call-id id})
                           view/rebuild-lines)]
                (cond-> s'
                  subagentDetails
                  (register-subagent id (:subagentChatId subagentDetails))))
              (do
                (protocol/approve-tool! (:server state) (:chat-id state) id)
                (let [s' (-> state (upsert-tool-call tool) view/rebuild-lines)]
                  (cond-> s'
                    subagentDetails
                    (register-subagent id (:subagentChatId subagentDetails)))))))))

      "toolCallRunning"
      (-> state
          (upsert-tool-call {:id        (:id content) :name (:name content)
                             :server    (:server content) :summary (:summary content)
                             :arguments (:arguments content) :state :running})
          view/rebuild-lines)

      "toolCalled"
      (let [{:keys [id name server summary arguments output error]} content
            out-text (when (seq (str output))
                       (if (> (count output) 8192)
                         (str (subs output 0 8192) "\n[truncated]")
                         output))]
        (-> state
            (upsert-tool-call {:id id :name name :server server
                               :summary summary :arguments arguments
                               :state :called :error? error :out-text out-text})
            view/rebuild-lines))

      "toolCallRejected"
      (-> state
          (upsert-tool-call {:id        (:id content) :name (:name content)
                             :server    (:server content) :summary (:summary content)
                             :arguments (:arguments content) :state :rejected})
          view/rebuild-lines)

      "reasonStarted"
      (-> state
          (update :items conj {:type :thinking :id (:id content) :text ""
                               :status :thinking :expanded? false :focused? false})
          view/rebuild-lines)

      "reasonText"
      (-> state
          (update :items
                  (fn [items]
                    (mapv #(if (and (= :thinking (:type %)) (= (:id content) (:id %)))
                             (update % :text str (:text content))
                             %)
                          items)))
          view/rebuild-lines)

      "reasonFinished"
      (-> state
          (update :items
                  (fn [items]
                    (mapv #(if (and (= :thinking (:type %)) (= (:id content) (:id %)))
                             (assoc % :status :thought)
                             %)
                          items)))
          view/rebuild-lines)

      "hookActionStarted"
      (-> state
          (update :items conj {:type :hook :id (:id content) :name (:name content)
                               :status :running :out-text nil :expanded? false :focused? false})
          view/rebuild-lines)

      "hookActionFinished"
      (-> state
          (update :items
                  (fn [items]
                    (mapv #(if (and (= :hook (:type %)) (= (:id content) (:id %)))
                             (assoc % :status (keyword (:status content))
                                      :out-text (:output content))
                             %)
                          items)))
          view/rebuild-lines)

      "usage"
      (assoc state :usage content)

      state)))
