(ns eca-bb.view-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca-bb.view :as view]))

(def ^:private render-tool-icon #'view/render-tool-icon)
(def ^:private pad-to-height #'view/pad-to-height)

(deftest divider-test
  (is (= "" (view/divider 0)))
  (is (= "──────────" (view/divider 10)))
  (is (= 40 (count (view/divider 40)))))

(deftest render-tool-icon-test
  (is (= "⏳" (render-tool-icon {:state :preparing})))
  (is (= "🚧" (render-tool-icon {:state :run})))
  (is (= "⏳" (render-tool-icon {:state :running})))
  (is (= "✅" (render-tool-icon {:state :called})))
  (is (= "❌" (render-tool-icon {:state :called :error? true})))
  (is (= "❌" (render-tool-icon {:state :rejected})))
  (is (= "⏳" (render-tool-icon {:state :unknown}))))

(deftest render-item-lines-test
  (testing ":user item"
    (let [lines (view/render-item-lines {:type :user :text "hello"} 80)]
      (is (= ["You: hello"] lines))))

  (testing ":assistant-text item"
    (let [lines (view/render-item-lines {:type :assistant-text :text "line1\nline2"} 80)]
      (is (= ["line1" "line2"] lines))))

  (testing ":streaming-text item"
    (let [lines (view/render-item-lines {:type :streaming-text :text "streaming"} 80)]
      (is (= ["streaming"] lines))))

  (testing ":tool-call with summary"
    (let [lines (view/render-item-lines {:type :tool-call :state :called :summary "read foo.clj"} 80)]
      (is (= 1 (count lines)))
      (is (clojure.string/includes? (first lines) "read foo.clj"))))

  (testing ":tool-call falls back to name"
    (let [lines (view/render-item-lines {:type :tool-call :state :called :name "read_file"} 80)]
      (is (clojure.string/includes? (first lines) "read_file"))))

  (testing "unknown type"
    (is (= [] (view/render-item-lines {:type :unknown} 80)))))

(deftest rebuild-chat-lines-test
  (testing "empty"
    (is (= [] (view/rebuild-chat-lines [] "" 80))))

  (testing "single user item"
    (let [lines (view/rebuild-chat-lines [{:type :user :text "hi"}] "" 80)]
      (is (= ["You: hi"] lines))))

  (testing "multiple items"
    (let [lines (view/rebuild-chat-lines
                  [{:type :user :text "hi"}
                   {:type :assistant-text :text "hello"}]
                  "" 80)]
      (is (= ["You: hi" "hello"] lines))))

  (testing "with current-text appended as streaming"
    (let [lines (view/rebuild-chat-lines [{:type :user :text "hi"}] "typing..." 80)]
      (is (= ["You: hi" "typing..."] lines))))

  (testing "empty current-text not appended"
    (let [lines (view/rebuild-chat-lines [{:type :user :text "hi"}] "" 80)]
      (is (= 1 (count lines))))))

(deftest pad-to-height-test
  (testing "shorter than height — pads at top with empty strings"
    (let [result (pad-to-height ["a" "b"] 5)]
      (is (= 5 (count result)))
      (is (= ["" "" "" "a" "b"] result))))

  (testing "exact height — no change"
    (is (= ["a" "b" "c"] (pad-to-height ["a" "b" "c"] 3))))

  (testing "longer than height — returned as-is"
    (let [lines ["a" "b" "c" "d" "e"]]
      (is (= lines (pad-to-height lines 3))))))

(deftest render-chat-test
  (let [base-state {:chat-lines ["line1" "line2" "line3" "line4" "line5"]
                    :height     7
                    :scroll-offset 0}]

    (testing "offset 0 — last N visible lines"
      (let [rendered (view/render-chat base-state)
            visible  (clojure.string/split-lines rendered)]
        (is (= 4 (count visible)))
        (is (clojure.string/includes? rendered "line5"))))

    (testing "mid-scroll — offset shifts window up"
      (let [rendered (view/render-chat (assoc base-state :scroll-offset 2))
            visible  (clojure.string/split-lines rendered)]
        (is (= 4 (count visible)))
        (is (clojure.string/includes? rendered "line3"))
        (is (not (clojure.string/includes? rendered "line5")))))

    (testing "empty chat lines — pads to visible height"
      (let [rendered (view/render-chat (assoc base-state :chat-lines []))]
        ;; 4 lines = 3 newline separators
        (is (= 3 (count (re-seq #"\n" rendered))))))))
