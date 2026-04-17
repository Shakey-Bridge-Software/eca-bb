(ns eca-bb.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [charm.components.text-input :as ti]
            [eca-bb.protocol :as protocol]
            [eca-bb.state :as state]))

(def ^:private flush-current-text #'state/flush-current-text)
(def ^:private upsert-tool-call   #'state/upsert-tool-call)
(def ^:private handle-content     #'state/handle-content)
(def ^:private handle-eca-tick    #'state/handle-eca-tick)

(defn- base-state []
  {:mode                  :chatting
   :trust                 false
   :chat-id               "chat1"
   :items                 []
   :current-text          ""
   :tool-calls            {}
   :pending-approval      nil
   :session-trusted-tools #{}
   :input                 (ti/text-input)
   :chat-lines            []
   :scroll-offset         0
   :width                 80
   :height                24
   :model                 nil
   :usage                 nil
   :server                nil
   :opts                  {:workspace "/tmp/test"}})

(deftest flush-current-text-test
  (testing "non-empty text appended and cleared"
    (let [s (flush-current-text (assoc (base-state) :current-text "hello"))]
      (is (= "" (:current-text s)))
      (is (= 1 (count (:items s))))
      (is (= {:type :assistant-text :text "hello"} (first (:items s))))))

  (testing "empty text is a no-op"
    (let [s (base-state)]
      (is (= s (flush-current-text s))))))

(deftest upsert-tool-call-test
  (testing "new tool call inserted"
    (let [s (upsert-tool-call (base-state) {:id "tc1" :name "read_file" :state :preparing})]
      (is (= :preparing (get-in s [:tool-calls "tc1" :state])))
      (is (some #(= "tc1" (:id %)) (:items s)))))

  (testing "existing tool call merged"
    (let [s0 (upsert-tool-call (base-state) {:id "tc1" :name "read_file" :state :preparing})
          s1 (upsert-tool-call s0 {:id "tc1" :state :called :error? false})]
      (is (= :called (get-in s1 [:tool-calls "tc1" :state])))
      (is (= "read_file" (get-in s1 [:tool-calls "tc1" :name])))
      (is (= 1 (count (filter #(= "tc1" (:id %)) (:items s1))))))))

(deftest handle-content-text-test
  (let [s (handle-content (base-state) {:content {:type "text" :text "hello"}})]
    (is (= "hello" (:current-text s))))

  (testing "appends to existing current-text"
    (let [s (-> (base-state)
                (assoc :current-text "he")
                (handle-content {:content {:type "text" :text "llo"}}))]
      (is (= "hello" (:current-text s))))))

(deftest handle-content-progress-test
  (testing "finished — flushes text, mode :ready"
    (let [s (handle-content
              (assoc (base-state) :current-text "streamed")
              {:content {:type "progress" :state "finished"}})]
      (is (= :ready (:mode s)))
      (is (= "" (:current-text s)))
      (is (some #(= "streamed" (:text %)) (:items s)))))

  (testing "non-finished — state unchanged"
    (let [base (assoc (base-state) :mode :chatting)
          s    (handle-content base {:content {:type "progress" :state "running"}})]
      (is (= :chatting (:mode s))))))

(deftest handle-content-tool-call-prepare-test
  (let [s (handle-content (base-state)
                           {:content {:type    "toolCallPrepare"
                                      :id      "tc1"
                                      :name    "read_file"
                                      :server  "local"
                                      :summary "read src/foo.clj"}})]
    (is (= :preparing (get-in s [:tool-calls "tc1" :state])))
    (is (some #(= "tc1" (:id %)) (:items s)))))

(deftest handle-content-tool-call-run-test
  (testing "trust=true — auto-approves, no mode change"
    (with-redefs [protocol/approve-tool! (fn [& _] nil)]
      (let [s (handle-content
                (assoc (base-state) :trust true)
                {:content {:type "toolCallRun" :id "tc1" :name "read_file"
                           :server "local" :manualApproval true}})]
        (is (not= :approving (:mode s)))
        (is (nil? (:pending-approval s))))))

  (testing "manualApproval=true trust=false — enters approving mode"
    (let [s (handle-content
              (base-state)
              {:content {:type "toolCallRun" :id "tc1" :name "read_file"
                         :server "local" :manualApproval true}})]
      (is (= :approving (:mode s)))
      (is (= {:chat-id "chat1" :tool-call-id "tc1"} (:pending-approval s)))))

  (testing "manualApproval=false — auto-approves regardless of trust"
    (with-redefs [protocol/approve-tool! (fn [& _] nil)]
      (let [s (handle-content
                (base-state)
                {:content {:type "toolCallRun" :id "tc1" :name "read_file"
                           :server "local" :manualApproval false}})]
        (is (not= :approving (:mode s)))))))

(deftest handle-content-tool-called-test
  (let [s (handle-content (base-state)
                           {:content {:type "toolCalled" :id "tc1" :name "read_file"
                                      :server "local" :error false}})]
    (is (= :called (get-in s [:tool-calls "tc1" :state])))))

(deftest handle-content-tool-call-rejected-test
  (let [s (handle-content (base-state)
                           {:content {:type "toolCallRejected" :id "tc1" :name "read_file"
                                      :server "local"}})]
    (is (= :rejected (get-in s [:tool-calls "tc1" :state])))))

(deftest handle-eca-tick-test
  (testing "reduces content notifications"
    (let [msgs [{:method "chat/contentReceived"
                 :params {:content {:type "text" :text "hi"}}}
                {:method "chat/contentReceived"
                 :params {:content {:type "text" :text " there"}}}]
          s    (handle-eca-tick (base-state) msgs)]
      (is (= "hi there" (:current-text s)))))

  (testing "prompt response sets chat-id and model"
    (let [msgs [{:type :eca-prompt-response :chat-id "new-chat" :model "claude-opus-4-7"}]
          s    (handle-eca-tick (base-state) msgs)]
      (is (= "new-chat" (:chat-id s)))
      (is (= "claude-opus-4-7" (:model s)))))

  (testing "unknown messages pass through unchanged"
    (let [base (base-state)
          s    (handle-eca-tick base [{:type :unknown :data "x"}])]
      (is (= (dissoc base :input) (dissoc s :input))))))
