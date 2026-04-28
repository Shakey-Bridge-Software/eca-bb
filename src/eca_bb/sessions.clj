(ns eca-bb.sessions
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn sessions-path []
  (str (System/getProperty "user.home") "/.cache/eca/eca-bb-sessions.edn"))

(defn load-chat-id
  "Returns persisted chat-id for workspace, or nil."
  [workspace]
  (try
    (let [f (java.io.File. (sessions-path))]
      (when (.exists f)
        (get (edn/read-string (slurp f)) workspace)))
    (catch Exception _ nil)))

(defn save-chat-id!
  "Saves chat-id for workspace. Passing nil removes the entry."
  [workspace chat-id]
  (let [path     (sessions-path)
        existing (try
                   (let [f (java.io.File. path)]
                     (if (.exists f) (edn/read-string (slurp f)) {}))
                   (catch Exception _ {}))
        updated  (if chat-id
                   (assoc existing workspace chat-id)
                   (dissoc existing workspace))]
    (io/make-parents path)
    (spit path (pr-str updated))))
