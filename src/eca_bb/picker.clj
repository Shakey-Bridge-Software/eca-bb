(ns eca-bb.picker
  "Picker overlay state helpers (model / agent / session / command).
  Pure transformations on a :picker map under state. Key handling for
  the :picking mode is added in a later step."
  (:require [clojure.string :as str]
            [charm.components.list :as cl]
            [charm.components.text-input :as ti]))

(defn item-display [kind item]
  (case kind
    :session (first item)
    :command (str (first item) "  —  " (second item))
    item))

(defn open-picker [state kind]
  (let [items (if (= :model kind) (:available-models state) (:available-agents state))]
    (if (empty? items)
      state
      (-> state
          (assoc :mode :picking
                 :picker {:kind     kind
                          :list     (cl/item-list items :height 8)
                          :all      items
                          :filtered items
                          :query    ""})
          (update :input ti/reset)))))

(defn open-session-picker [state session-pairs]
  (let [labels (mapv first session-pairs)]
    (-> state
        (assoc :mode :picking
               :picker {:kind     :session
                        :list     (cl/item-list labels :height 8)
                        :all      session-pairs
                        :filtered session-pairs
                        :query    ""})
        (update :input ti/reset))))

(defn filter-picker [state ch]
  (let [query    (str (get-in state [:picker :query]) ch)
        kind     (get-in state [:picker :kind])
        all      (get-in state [:picker :all])
        filtered (filterv #(str/includes? (str/lower-case (item-display kind %))
                                          (str/lower-case query))
                          all)
        labels   (mapv #(item-display kind %) filtered)]
    (-> state
        (assoc-in [:picker :query] query)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items labels))))

(defn unfilter-picker [state]
  (let [query    (get-in state [:picker :query])
        new-q    (if (seq query) (subs query 0 (dec (count query))) "")
        kind     (get-in state [:picker :kind])
        all      (get-in state [:picker :all])
        filtered (if (seq new-q)
                   (filterv #(str/includes? (str/lower-case (item-display kind %))
                                            (str/lower-case new-q))
                            all)
                   all)
        labels   (mapv #(item-display kind %) filtered)]
    (-> state
        (assoc-in [:picker :query] new-q)
        (assoc-in [:picker :filtered] filtered)
        (update-in [:picker :list] cl/set-items labels))))
