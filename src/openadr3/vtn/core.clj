(ns openadr3.vtn.core
  "Entry point for the OpenADR 3.1.0 VTN server."
  (:require [com.stuartsierra.component :as component]
            [openadr3.vtn.system :as system]
            [clojure.tools.logging :as log]))

(defn -main [& _args]
  (log/info "Starting OpenADR 3.1.0 VTN")
  (let [sys (-> (system/system-map)
                component/start)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(component/stop sys)))
    (log/info "VTN started")))
