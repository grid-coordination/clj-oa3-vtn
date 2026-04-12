(ns openadr3.vtn.core
  "Entry point for the OpenADR 3.1.0 VTN server."
  (:require [com.stuartsierra.component :as component]
            [com.brunobonacci.mulog :as mu]
            [openadr3.vtn.system :as system]))

(defn -main [& _args]
  (mu/start-publisher! {:type :console})
  (mu/log ::starting)
  (let [sys (-> (system/system-map)
                component/start)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(component/stop sys)))
    (mu/log ::started)))
