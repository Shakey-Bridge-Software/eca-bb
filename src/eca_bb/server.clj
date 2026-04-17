(ns eca-bb.server
  (:require [cheshire.core :as json]
            [babashka.process :as proc])
  (:import [java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn find-eca-binary
  "Finds the ECA binary. Checks PATH first, then common cache locations."
  []
  (or (some-> (proc/process ["which" "eca"] {:err :string :out :string})
              deref :out clojure.string/trim
              not-empty)
      (let [cache-path (str (System/getProperty "user.home") "/.cache/nvim/eca/eca")]
        (when (.exists (java.io.File. cache-path))
          cache-path))))

(defn spawn!
  "Spawns the ECA server process. Returns a map with :process, :reader, :writer, :queue."
  ([] (spawn! nil))
  ([eca-path]
   (let [binary (or eca-path (find-eca-binary))
         _ (when-not binary
             (throw (ex-info "ECA binary not found. Install ECA or set path." {})))
         p (proc/process [binary "server"]
                         {:err :inherit
                          :shutdown proc/destroy-tree})
         reader (BufferedReader. (InputStreamReader. (:out p) "UTF-8"))
         writer (BufferedWriter. (OutputStreamWriter. (:in p) "UTF-8"))
         queue (LinkedBlockingQueue.)]
     {:process p
      :reader reader
      :writer writer
      :queue queue
      :alive? (atom true)})))

;; --- JSON-RPC framing ---

(defn- read-content-length
  "Reads headers from the reader until the blank line, extracts Content-Length."
  [^BufferedReader reader]
  (loop [content-length nil]
    (let [line (.readLine reader)]
      (cond
        (nil? line) nil ;; EOF
        (= "" line) content-length ;; blank line = end of headers
        :else
        (let [cl (when (.startsWith line "Content-Length: ")
                   (parse-long (subs line 16)))]
          (recur (or cl content-length)))))))

(defn- read-body
  "Reads exactly n chars from the reader."
  [^BufferedReader reader n]
  (let [buf (char-array n)]
    (loop [offset 0]
      (when (< offset n)
        (let [read (.read reader buf offset (- n offset))]
          (when (pos? read)
            (recur (+ offset read))))))
    (String. buf)))

(defn read-message!
  "Blocking read of one JSON-RPC message from the reader. Returns parsed map or nil on EOF."
  [^BufferedReader reader]
  (when-let [content-length (read-content-length reader)]
    (let [body (read-body reader content-length)]
      (json/parse-string body true))))

(defn write-message!
  "Writes a JSON-RPC message with Content-Length framing."
  [^BufferedWriter writer msg]
  (let [body (json/generate-string msg)
        bytes (.getBytes body "UTF-8")
        header (str "Content-Length: " (count bytes) "\r\n\r\n")]
    (.write writer header)
    (.write writer body)
    (.flush writer)))

;; --- Reader thread ---

(defn start-reader!
  "Starts a background thread that reads JSON-RPC messages and dispatches them.
   Responses (have :id, no :method) go to pending-requests callbacks.
   Notifications (have :method) go onto the queue for charm.clj."
  [{:keys [reader queue alive? pending-requests]}]
  (let [t (Thread.
           (fn []
             (try
               (while @alive?
                 (when-let [msg (read-message! reader)]
                   (if (and (:id msg) (not (:method msg)))
                     ;; Response — invoke pending callback
                     (when-let [cb (get @pending-requests (:id msg))]
                       (swap! pending-requests dissoc (:id msg))
                       (cb msg))
                     ;; Notification — queue for charm.clj
                     (.put queue msg))))
               (catch Exception e
                 (when @alive?
                   (.put queue {:type :reader-error :error (str e)}))))))]
    (.setDaemon t true)
    (.setName t "eca-bb-reader")
    (.start t)
    t))

(defn read-batch!
  "Non-blocking drain of all available messages from the queue.
   Returns a vector of messages (may be empty)."
  [^LinkedBlockingQueue queue timeout-ms]
  (let [first-msg (.poll queue timeout-ms TimeUnit/MILLISECONDS)]
    (if first-msg
      (loop [msgs [first-msg]]
        (if-let [next-msg (.poll queue)]
          (recur (conj msgs next-msg))
          msgs))
      [])))

;; --- Lifecycle ---

(defn shutdown!
  "Gracefully stops the server."
  [{:keys [process alive?]}]
  (reset! alive? false)
  (when process
    (proc/destroy-tree process)))
