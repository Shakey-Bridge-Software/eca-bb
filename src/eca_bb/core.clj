(ns eca-bb.core
  (:require [babashka.cli :as cli]
            [charm.program :as program]
            [eca-bb.state :as state]
            [eca-bb.view :as view]))

(def ^:private cli-spec
  {:trust     {:desc "Auto-approve all tool calls" :coerce :boolean}
   :workspace {:desc "Workspace path" :default (System/getProperty "user.dir")}
   :model     {:desc "Model to use"}
   :agent     {:desc "Agent to use"}
   :eca       {:desc "Path to ECA binary"}})

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec cli-spec})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (print "\033[?1049l\033[?25h")
                                 (flush))))
    (program/run {:init       (state/make-init opts)
                  :update     state/update-state
                  :view       view/view
                  :alt-screen true
                  :fps        20})))
