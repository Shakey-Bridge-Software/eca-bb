(ns eca-bb.view
  (:require [clojure.string :as str]
            [charm.components.text-input :as ti]))

(defn divider [width]
  (apply str (repeat width "─")))

(defn- render-tool-icon [tool-call]
  (case (:state tool-call)
    :preparing "⏳"
    :run       "🚧"
    :running   "⏳"
    :called    (if (:error? tool-call) "❌" "✅")
    :rejected  "❌"
    "⏳"))

(defn render-item-lines [item _width]
  (case (:type item)
    :user
    [(str "You: " (:text item))]

    (:assistant-text :streaming-text)
    (str/split-lines (str (:text item)))

    :tool-call
    [(str (render-tool-icon item) " " (or (:summary item) (:name item)))]

    []))

(defn rebuild-chat-lines [items current-text width]
  (->> (concat items
               (when (seq current-text)
                 [{:type :streaming-text :text current-text}]))
       (mapcat #(render-item-lines % width))
       vec))

(defn- pad-to-height [lines height]
  (let [n (count lines)]
    (if (>= n height)
      lines
      (into (vec (repeat (- height n) "")) lines))))

(defn render-chat [state]
  (let [visible-height (max 1 (- (:height state) 3))
        lines          (:chat-lines state)
        total          (count lines)
        offset         (:scroll-offset state)
        end            (max 0 (- total offset))
        start          (max 0 (- end visible-height))
        visible        (pad-to-height (subvec lines start end) visible-height)]
    (str/join "\n" visible)))

(defn render-approval [state]
  (when-let [{:keys [tool-call-id]} (:pending-approval state)]
    (let [tool    (get-in state [:tool-calls tool-call-id])
          summary (or (:summary tool) (:name tool) "tool call")]
      (str "🚧 " summary "\n[y] approve  [Y] always  [n] reject"))))

(defn render-status-bar [state]
  (let [workspace (-> (get-in state [:opts :workspace] ".")
                      java.io.File.
                      .getName)
        model     (or (:model state) "…")
        tokens    (some-> (:usage state) :sessionTokens (str " tok"))
        trust     (if (:trust state) "TRUST" "SAFE")]
    (str/join "  " (remove nil? [workspace model tokens trust]))))

(defn view [state]
  (let [input-area (if (= :approving (:mode state))
                     (or (render-approval state) "")
                     (ti/text-input-view (:input state)))]
    (str (render-chat state)
         "\n" (divider (:width state))
         "\n" input-area
         "\n" (render-status-bar state))))
