(ns eca-bb.state
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [charm.program :as program]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]
            [charm.message :as msg]
            [eca-bb.server :as server]
            [eca-bb.protocol :as protocol]
            [eca-bb.sessions :as sessions]
            [eca-bb.upgrade :as upgrade]
            [eca-bb.view :as view]
            [eca-bb.chat :as chat]
            [eca-bb.picker :as picker]
            [eca-bb.commands :as commands]))

;; Expose last-known state for nREPL inspection
(def debug-state (atom nil))

;; --- Commands ---

(defn- drain-queue-cmd [queue]
  (program/cmd
    (fn []
      {:type :eca-tick
       :msgs (server/read-batch! queue 50)})))

(defn- init-cmd [srv workspace]
  (program/cmd
    (fn []
      (try
        {:type :eca-initialized :result (protocol/initialize! srv workspace)}
        (catch Exception e
          {:type :eca-error :error (ex-message e)})))))

(defn- shutdown-cmd [srv]
  (program/sequence-cmds
    (program/cmd (fn [] (try (protocol/shutdown! srv) (catch Exception _)) nil))
    (program/cmd (fn [] (server/shutdown! srv) nil))
    program/quit-cmd))

(defn- start-login-cmd [srv pending-message]
  (program/cmd
    (fn []
      (let [providers-result (promise)
            _                (protocol/providers-list! srv
                               (fn [r] (deliver providers-result (or (:result r) {}))))]
        (let [providers (-> (deref providers-result 10000 {:providers []}) :providers)
              provider  (first (filter #(contains? #{"unauthenticated" "expired"}
                                                   (get-in % [:auth :status]))
                                       providers))]
          (if-not provider
            {:type :eca-error :error "Login required but no unauthenticated provider found"}
            (let [login-result (promise)
                  _            (protocol/providers-login! srv (:id provider) nil
                                 (fn [r] (deliver login-result (or (:result r) (:error r)))))]
              {:type            :eca-login-action
               :provider        (:id provider)
               :action          (deref login-result 10000 nil)
               :pending-message pending-message})))))))

(defn- choose-login-method-cmd [srv provider method]
  (program/cmd
    (fn []
      (let [result (promise)
            _      (protocol/providers-login! srv provider method
                     (fn [r] (deliver result (or (:result r) (:error r)))))]
        {:type     :eca-login-action
         :provider provider
         :action   (deref result 10000 nil)}))))

(defn- submit-login-cmd [srv provider collected pending-message]
  (program/cmd
    (fn []
      (let [result (promise)
            _      (protocol/providers-login-input! srv provider collected
                     (fn [r] (deliver result (or (:result r) (:error r)))))]
        (let [r (deref result 10000 ::timeout)]
          (cond
            (= r ::timeout)        {:type :eca-error :error "Login timed out"}
            (= "done" (:action r)) {:type :eca-login-complete :pending-message pending-message}
            :else                  {:type :eca-error :error (str "Login failed: " r)}))))))

;; --- Login notification handler ---

(defn- handle-providers-updated [state provider-status]
  (let [auth-status (get-in provider-status [:auth :status])
        provider-id (:id provider-status)]
    (if (and (= :login (:mode state))
             (contains? #{"authenticated" "expiring"} auth-status)
             (= provider-id (get-in state [:login :provider])))
      (let [pending   (:pending-message state)
            srv       (:server state)
            opts      (:opts state)
            new-state (-> state
                          (assoc :mode :chatting)
                          (dissoc :login)
                          (update :input ti/blur))]
        [new-state (when pending
                     (program/cmd (fn []
                                    (chat/send-chat-prompt! srv nil pending opts)
                                    nil)))])
      [state nil])))

(defn- handle-eca-notification [state notification]
  (case (:method notification)
    "chat/contentReceived"
    ;; ECA echoes the user's message back (role:"user") so editor plugins that don't
    ;; track sent messages can display it. We render user messages immediately on send,
    ;; so consume the echo via :echo-pending flag and skip rendering it.
    ;; Non-echo role:"user" text is a replayed historical message (session resume):
    ;; flush :current-text first so prior assistant responses land in the right position.
    ;; Non-text role:"user" content (e.g. progress start markers) is ignored.
    ;; Route by chatId: any message from a known sub-agent chat goes to the spawn tool
    ;; call's :sub-items. parentChatId is not required — ECA omits it on role:"user"
    ;; messages (the task prompt sent to the sub-agent).
    (let [params  (:params notification)
          content (:content params)]
      (if-let [parent-idx (get (:subagent-chats state) (:chatId params))]
        [(-> state
             (update-in [:items parent-idx :sub-items]
                        (fn [subs]
                          (if-let [item (chat/content->item params)]
                            (conj (or subs []) item)
                            (or subs []))))
             view/rebuild-lines)
         nil]
        (if (= "user" (:role params))
          (if (= "text" (:type content))
            (cond
              ;; Sub-agent task prompt: parentChatId marks it as machine-generated,
              ;; never typed by the human — render as assistant text, not user input.
              (:parentChatId params)
              [(-> state
                   chat/flush-current-text
                   (update :items conj {:type :assistant-text :text (or (:text content) "")})
                   view/rebuild-lines)
               nil]

              (:echo-pending state)
              [(assoc state :echo-pending false) nil]

              :else
              [(-> state
                   chat/flush-current-text
                   (update :items conj {:type :user :text (or (:text content) "")})
                   view/rebuild-lines)
               nil])
            [state nil])
          [(chat/handle-content state params) nil])))

    "providers/updated"
    (handle-providers-updated state (:params notification))

    "$/progress"
    (let [{:keys [type taskId title]} (:params notification)]
      [(case type
         "start"  (assoc-in state [:init-tasks taskId] {:title title :done? false})
         "finish" (if (contains? (:init-tasks state) taskId)
                    (assoc-in state [:init-tasks taskId :done?] true)
                    state)
         state)
       nil])

    "$/showMessage"
    (let [text (or (get-in notification [:params :message]) "Server message")]
      [(-> state
           (update :items conj {:type :system :text text})
           view/rebuild-lines)
       nil])

    "config/updated"
    (let [chat (get-in notification [:params :chat])
          s'   (cond-> state
                 (:models chat)                  (assoc :available-models (:models chat))
                 (:agents chat)                  (assoc :available-agents (:agents chat))
                 (contains? chat :selectModel)   (assoc :selected-model (:selectModel chat))
                 (contains? chat :selectAgent)   (assoc :selected-agent (:selectAgent chat))
                 (contains? chat :variants)      (assoc :available-variants (:variants chat))
                 (contains? chat :selectVariant) (assoc :selected-variant (:selectVariant chat))
                 (:welcomeMessage chat)          (update :items conj {:type :assistant-text
                                                                       :text (:welcomeMessage chat)}))]
      [(if (:welcomeMessage chat) (view/rebuild-lines s') s') nil])

    "chat/opened"
    (let [{:keys [chatId title]} (:params notification)]
      [(-> state
           (assoc :chat-id chatId)
           (assoc :chat-title title))
       nil])

    "chat/cleared"
    (let [clear-msgs? (get-in notification [:params :messages])]
      [(cond-> state
         clear-msgs? (-> (assoc :items [])
                         (assoc :current-text "")
                         (assoc :chat-lines [])
                         (assoc :scroll-offset 0)))
       nil])

    [state nil]))

(defn- handle-eca-tick [state msgs]
  (reduce
    (fn [[s cmd] m]
      (cond
        (= :reader-error (:type m))
        [(-> s
             (assoc :mode :ready)
             (update :items conj {:type :system
                                   :text (str "ECA disconnected: " (:error m))})
             (update :input ti/focus)
             view/rebuild-lines)
         nil]

        (= :eca-prompt-response (:type m))
        (let [s' (cond-> s
                   (:chat-id m) (assoc :chat-id (:chat-id m))
                   (:model m)   (assoc :model (:model m)))]
          (if (= "login" (:status m))
            [s' (program/batch cmd (start-login-cmd (:server s') (:pending-message s')))]
            [s' cmd]))

        (:method m)
        (let [[s'' extra-cmd] (handle-eca-notification s m)]
          [s'' (program/batch cmd extra-cmd)])

        :else [s cmd]))
    [state nil]
    msgs))


(defn- initial-state [srv opts]
  {:mode                  :connecting
   :server                srv
   :opts                  opts
   :trust                 (boolean (:trust opts))
   :chat-id               nil
   :chat-title            nil
   :items                 []
   :current-text          ""
   :tool-calls            {}
   :pending-approval      nil
   :pending-message       nil
   :echo-pending          false
   :session-trusted-tools #{}
   :init-tasks            {}
   :available-models      []
   :available-agents      []
   :available-variants    []
   :selected-model        nil
   :selected-agent        nil
   :selected-variant      nil
   :input                 (ti/text-input)
   :input-history         []
   :history-idx           nil
   :focus-path            nil
   :subagent-chats        {}
   :chat-lines            []
   :scroll-offset         0
   :width                 80
   :height                24
   :model                 nil
   :usage                 nil})

(defn make-init [opts]
  (fn []
    (let [workspace (:workspace opts)
          binary    (or (:eca opts) (server/find-eca-binary))
          srv       (-> (server/spawn! {:path binary})
                        (assoc :pending-requests protocol/pending-requests))
          warn      (upgrade/check-version binary)
          init-s    (cond-> (initial-state srv opts)
                      warn (-> (update :items conj {:type :system :text warn})
                               view/rebuild-lines))]
      (server/start-reader! srv)
      [init-s (init-cmd srv workspace)])))

;; --- Update ---

(defn update-state [state msg]
  (reset! debug-state {:state (dissoc state :server :input)
                        :msg-type (or (:type msg) (:method msg))
                        :queue-size (when-let [q (get-in state [:server :queue])] (.size q))})
  (let [queue (get-in state [:server :queue])]
    (cond
      (= :window-size (:type msg))
      [(-> state
           (assoc :width (:width msg) :height (:height msg))
           view/rebuild-lines)
       nil]

      (= :eca-initialized (:type msg))
      [(-> state (assoc :mode :ready) (update :input ti/focus))
       (drain-queue-cmd queue)]

      (= :eca-error (:type msg))
      [(-> state
           (assoc :mode :ready)
           (update :items conj {:type :assistant-text :text (str "Error: " (:error msg))})
           (update :input ti/focus)
           view/rebuild-lines)
       nil]

      (= :eca-tick (:type msg))
      (let [[new-state extra-cmd] (handle-eca-tick state (:msgs msg))]
        [new-state (program/batch extra-cmd (drain-queue-cmd queue))])

      ;; Login: action received from providers/login
      (= :eca-login-action (:type msg))
      (let [{:keys [provider action]} msg
            pending (or (:pending-message msg) (get-in state [:login :pending-message]))]
        (cond
          (nil? action)
          [(-> state
               (assoc :mode :ready)
               (update :input ti/focus)
               (update :items conj {:type :system :text "Login failed: timed out"})
               view/rebuild-lines)
           nil]

          (= "done" (:action action))
          (do
            (when pending
              (chat/send-chat-prompt! (:server state) nil pending (:opts state)))
            [(-> state (assoc :mode :chatting) (dissoc :login) (update :input ti/blur)) nil])

          :else
          (let [needs-input? (or (= "input" (:action action))
                                 (and (= "authorize" (:action action))
                                      (seq (:fields action))))
                login-state  {:provider        provider
                               :action          action
                               :field-idx       0
                               :collected       {}
                               :pending-message pending}]
            [(-> state
                 (assoc :mode :login :login login-state)
                 (update :input #(if needs-input? (ti/focus %) (ti/blur %))))
             nil])))

      ;; Login: input submitted successfully
      (= :eca-login-complete (:type msg))
      (let [pending (:pending-message msg)]
        (when pending
          (chat/send-chat-prompt! (:server state) nil pending (:opts state)))
        [(-> state (assoc :mode :chatting) (dissoc :login) (update :input ti/blur)) nil])

      ;; Session list loaded from chat/list response
      (= :chat-list-loaded (:type msg))
      (let [chats  (:chats msg)
            error? (:error? msg)
            pairs  (mapv (fn [{:keys [id title messageCount]}]
                           (let [t   (if (seq title) title (subs (or id "") 0 (min 8 (count (or id "")))))
                                 cnt (when messageCount (str messageCount " msgs"))]
                             [(str/join "  •  " (remove nil? [t cnt])) id]))
                         chats)
            s'     (picker/open-session-picker state pairs)]
        [(if error?
           (-> s' (update :items conj {:type :system :text "⚠ Could not load sessions"}) view/rebuild-lines)
           s')
         nil])

      (or (msg/quit? msg)
          (and (msg/key-press? msg) (msg/key-match? msg "ctrl+c")))
      [state (shutdown-cmd (:server state))]

      ;; Ctrl+L: open model picker (same guard as /model command)
      (and (msg/key-press? msg)
           (msg/key-match? msg "ctrl+l")
           (= :ready (:mode state)))
      (commands/cmd-open-model-picker state)

      ;; Focus navigation: Tab/Shift+Tab cycles through focusable items in render order
      (and (msg/key-press? msg)
           (msg/key-match? msg :tab)
           (not (:shift msg))
           (not (:alt msg))
           (#{:ready :chatting} (:mode state)))
      (let [paths (chat/focusable-paths (:items state))]
        (if (empty? paths)
          [state nil]
          (let [cur     (:focus-path state)
                cur-idx (when cur (first (keep-indexed #(when (= cur %2) %1) paths)))
                next    (if (nil? cur-idx)
                          (first paths)
                          (nth paths (mod (inc cur-idx) (count paths))))]
            [(-> state (assoc :focus-path next) chat/sync-focus view/rebuild-lines) nil])))

      ;; Shift+Tab: reverse focus (kept as-is; may not work in tmux with mouse on)
      (and (msg/key-press? msg)
           (msg/key-match? msg :tab)
           (:shift msg)
           (#{:ready :chatting} (:mode state)))
      (let [paths (chat/focusable-paths (:items state))]
        (if (empty? paths)
          [state nil]
          (let [cur     (:focus-path state)
                n       (count paths)
                cur-idx (when cur (first (keep-indexed #(when (= cur %2) %1) paths)))
                prev    (if (nil? cur-idx)
                          (last paths)
                          (nth paths (mod (dec cur-idx) n)))]
            [(-> state (assoc :focus-path prev) chat/sync-focus view/rebuild-lines) nil])))

      ;; Up/Down arrows navigate between focusable items when focus is active;
      ;; fall through to history/scroll handlers when no focus is set
      (and (msg/key-press? msg)
           (msg/key-match? msg :up)
           (some? (:focus-path state))
           (#{:ready :chatting} (:mode state)))
      (let [paths (chat/focusable-paths (:items state))]
        (if (empty? paths)
          [state nil]
          (let [cur     (:focus-path state)
                n       (count paths)
                cur-idx (when cur (first (keep-indexed #(when (= cur %2) %1) paths)))
                prev    (if (nil? cur-idx)
                          (last paths)
                          (nth paths (mod (dec cur-idx) n)))]
            [(-> state (assoc :focus-path prev) chat/sync-focus view/rebuild-lines) nil])))

      (and (msg/key-press? msg)
           (msg/key-match? msg :down)
           (some? (:focus-path state))
           (#{:ready :chatting} (:mode state)))
      (let [paths (chat/focusable-paths (:items state))]
        (if (empty? paths)
          [state nil]
          (let [cur     (:focus-path state)
                cur-idx (when cur (first (keep-indexed #(when (= cur %2) %1) paths)))
                next    (if (nil? cur-idx)
                          (first paths)
                          (nth paths (mod (inc cur-idx) (count paths))))]
            [(-> state (assoc :focus-path next) chat/sync-focus view/rebuild-lines) nil])))

      ;; Focus: Escape clears focus without changing mode
      (and (msg/key-press? msg)
           (msg/key-match? msg :escape)
           (some? (:focus-path state))
           (#{:ready :chatting} (:mode state)))
      [(-> state (assoc :focus-path nil) chat/sync-focus view/rebuild-lines) nil]

      ;; Focus: Enter toggles :expanded? on focused item
      (and (msg/key-press? msg)
           (msg/key-match? msg :enter)
           (some? (:focus-path state))
           (#{:ready :chatting} (:mode state)))
      (let [[i j] (:focus-path state)
            item-path (if j [:items i :sub-items j] [:items i])]
        [(-> state (update-in item-path update :expanded? not) view/rebuild-lines) nil])

      (and (msg/key-press? msg)
           (msg/key-match? msg :enter)
           (= :ready (:mode state)))
      (let [text (str/trim (ti/value (:input state)))]
        (cond
          (str/starts-with? text "/")
          (commands/dispatch-command state text)

          (seq text)
          (let [new-state (-> state
                              (update :items conj {:type :user :text text})
                              (assoc :mode :chatting :pending-message text :echo-pending true)
                              (update :input #(-> % ti/reset ti/blur))
                              (update :input-history conj text)
                              (assoc :history-idx nil)
                              view/rebuild-lines)]
            (chat/send-chat-prompt! (:server state) (:chat-id state) text (:opts state))
            [new-state nil])

          :else [state nil]))

      (and (msg/key-press? msg)
           (msg/key-match? msg :escape)
           (= :chatting (:mode state)))
      (do
        (when (:chat-id state)
          (protocol/stop-prompt! (:server state) (:chat-id state)))
        [(-> state (assoc :mode :ready) (update :input ti/focus)) nil])

      ;; Login: choose method with digit key
      (and (msg/key-press? msg)
           (= :login (:mode state))
           (= "choose-method" (get-in state [:login :action :action]))
           (re-matches #"[1-9]" (str (:key msg))))
      (let [idx    (dec (parse-long (str (:key msg))))
            methods (get-in state [:login :action :methods])
            method  (nth methods idx nil)]
        (if method
          [state (choose-login-method-cmd (:server state)
                                          (get-in state [:login :provider])
                                          (:key method))]
          [state nil]))

      ;; Login: enter to submit input field
      (and (msg/key-press? msg)
           (msg/key-match? msg :enter)
           (= :login (:mode state))
           (let [action-type (get-in state [:login :action :action])]
             (or (= "input" action-type)
                 (and (= "authorize" action-type)
                      (seq (get-in state [:login :action :fields]))))))
      (let [login     (:login state)
            fields    (get-in login [:action :fields])
            field     (nth fields (:field-idx login) nil)
            value     (str/trim (ti/value (:input state)))
            collected (assoc (:collected login) (:key field) value)
            next-idx  (inc (:field-idx login))]
        (if (< next-idx (count fields))
          [(-> state
               (update :login assoc :field-idx next-idx :collected collected)
               (update :input #(-> % ti/reset ti/focus)))
           nil]
          [(-> state (update :input #(-> % ti/reset ti/blur)))
           (submit-login-cmd (:server state)
                             (:provider login)
                             collected
                             (:pending-message login))]))

      ;; Login: escape to cancel
      (and (msg/key-press? msg)
           (msg/key-match? msg :escape)
           (= :login (:mode state)))
      [(-> state
           (assoc :mode :ready)
           (dissoc :login :pending-message)
           (update :input ti/focus))
       nil]

      (and (msg/key-press? msg)
           (msg/key-match? msg "y")
           (= :approving (:mode state)))
      (let [{:keys [chat-id tool-call-id]} (:pending-approval state)]
        (protocol/approve-tool! (:server state) chat-id tool-call-id)
        [(assoc state :mode :chatting :pending-approval nil) nil])

      (and (msg/key-press? msg)
           (msg/key-match? msg "Y")
           (= :approving (:mode state)))
      (let [{:keys [chat-id tool-call-id]} (:pending-approval state)
            tool-name (get-in state [:tool-calls tool-call-id :name])]
        (protocol/approve-tool! (:server state) chat-id tool-call-id)
        [(-> state
             (assoc :mode :chatting :pending-approval nil)
             (update :session-trusted-tools conj tool-name))
         nil])

      (and (msg/key-press? msg)
           (msg/key-match? msg "n")
           (= :approving (:mode state)))
      (let [{:keys [chat-id tool-call-id]} (:pending-approval state)]
        (protocol/reject-tool! (:server state) chat-id tool-call-id)
        [(assoc state :mode :chatting :pending-approval nil) nil])

      ;; Picker: Enter to select
      (and (msg/key-press? msg)
           (msg/key-match? msg :enter)
           (= :picking (:mode state)))
      (let [{:keys [kind list filtered]} (:picker state)]
        (case kind
          (:model :agent)
          (let [selected (cl/selected-item list)]
            (if selected
              (do
                (if (= :model kind)
                  (protocol/selected-model-changed! (:server state) selected)
                  (protocol/selected-agent-changed! (:server state) selected))
                [(-> state
                     (assoc :mode :ready)
                     (assoc (if (= :model kind) :selected-model :selected-agent) selected)
                     (cond-> (= :model kind) (assoc :selected-variant nil))
                     (assoc-in [:opts (if (= :model kind) :model :agent)] selected)
                     (dissoc :picker)
                     (update :input ti/focus))
                 nil])
              [state nil]))

          :session
          (let [idx             (cl/selected-index list)
                [_display chat-id] (when (and (some? idx) (< idx (count filtered)))
                                     (nth filtered idx))]
            (when chat-id
              (sessions/save-chat-id! (get-in state [:opts :workspace]) chat-id))
            [(-> state
                 (assoc :mode :ready
                        :items []
                        :chat-lines []
                        :scroll-offset 0)
                 (assoc :chat-id (or chat-id (:chat-id state)))
                 (dissoc :picker)
                 (update :input ti/focus))
             (when chat-id (sessions/open-chat-cmd (:server state) chat-id))])

          :command
          (let [idx           (cl/selected-index list)
                [cmd-name _]  (when (and (some? idx) (< idx (count filtered)))
                                (nth filtered idx))]
            (if cmd-name
              (commands/run-handler-from-picker
                (-> state (dissoc :picker) (assoc :mode :ready))
                cmd-name)
              [state nil]))))

      ;; Picker: Escape to cancel
      (and (msg/key-press? msg)
           (msg/key-match? msg :escape)
           (= :picking (:mode state)))
      [(-> state
           (assoc :mode :ready)
           (dissoc :picker)
           (update :input ti/focus))
       nil]

      ;; Picker: Backspace removes last filter char
      ;; For the command picker, backspace on empty query exits to :ready
      (and (msg/key-press? msg)
           (msg/key-match? msg :backspace)
           (= :picking (:mode state)))
      (if (and (= :command (get-in state [:picker :kind]))
               (= "" (get-in state [:picker :query])))
        [(-> state (assoc :mode :ready) (dissoc :picker) (update :input ti/focus)) nil]
        [(picker/unfilter-picker state) nil])

      ;; Picker: printable char narrows filter
      (and (commands/printable-char? msg)
           (= :picking (:mode state)))
      [(picker/filter-picker state (:key msg)) nil]

      ;; Picker: navigation keys passed to list-update
      (= :picking (:mode state))
      (let [[new-list _] (cl/list-update (get-in state [:picker :list]) msg)]
        [(assoc-in state [:picker :list] new-list) nil])

      ;; Input history navigation (up/down in :ready mode)
      (and (msg/key-press? msg)
           (msg/key-match? msg :up)
           (= :ready (:mode state)))
      (let [history (:input-history state)
            cur-idx (:history-idx state)
            new-idx (if (nil? cur-idx)
                      (dec (count history))
                      (max 0 (dec cur-idx)))]
        (if (seq history)
          [(-> state
               (assoc :history-idx new-idx)
               (update :input #(ti/set-value % (nth history new-idx))))
           nil]
          [state nil]))

      (and (msg/key-press? msg)
           (msg/key-match? msg :down)
           (= :ready (:mode state))
           (some? (:history-idx state)))
      (let [history (:input-history state)
            new-idx (inc (:history-idx state))]
        (if (< new-idx (count history))
          [(-> state
               (assoc :history-idx new-idx)
               (update :input #(ti/set-value % (nth history new-idx))))
           nil]
          [(-> state
               (assoc :history-idx nil)
               (update :input #(ti/set-value % "")))
           nil]))

      ;; PgUp/PgDn scroll (full page)
      (and (msg/key-press? msg)
           (msg/key-match? msg :page-up)
           (not (#{:approving :picking} (:mode state))))
      (let [max-offset (max 0 (- (count (:chat-lines state)) (- (:height state) 5)))
            page       (max 1 (- (:height state) 5))]
        [(update state :scroll-offset #(min max-offset (+ % page))) nil])

      (and (msg/key-press? msg)
           (msg/key-match? msg :page-down)
           (not (#{:approving :picking} (:mode state))))
      (let [page (max 1 (- (:height state) 5))]
        [(update state :scroll-offset #(max 0 (- % page))) nil])

      ;; Mouse wheel scroll (3 lines per tick)
      (and (msg/wheel-up? msg)
           (not (#{:approving :picking} (:mode state))))
      (let [max-offset (max 0 (- (count (:chat-lines state)) (- (:height state) 5)))]
        [(update state :scroll-offset #(min max-offset (+ % 3))) nil])

      (and (msg/wheel-down? msg)
           (not (#{:approving :picking} (:mode state))))
      [(update state :scroll-offset #(max 0 (- % 3))) nil]

      ;; Autocomplete: "/" as first char in empty :ready input opens command picker
      (and (commands/printable-char? msg)
           (= "/" (:key msg))
           (= :ready (:mode state))
           (= "" (str/trim (ti/value (:input state)))))
      [(commands/open-command-picker state) nil]

      :else
      (let [[new-input cmd] (ti/text-input-update (:input state) msg)]
        [(assoc state :input new-input) cmd]))))
