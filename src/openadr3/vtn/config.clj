(ns openadr3.vtn.config
  "Configuration component. Loads config.edn from classpath."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]))

(def defaults
  {:ven-port 8080
   :bl-port 8081
   :context-path "/openadr3/3.1.0"
   :mqtt-broker-url "mqtt://localhost:1883"
   :mqtt-retained false
   :storage-backend :memory})

(defn load-config
  "Load config.edn from classpath and merge with defaults."
  []
  (let [resource (io/resource "config.edn")
        file-config (when resource
                      (edn/read-string (slurp resource)))]
    (merge defaults file-config)))

(defrecord Config [config]
  component/Lifecycle
  (start [this]
    (if config
      this
      (assoc this :config (load-config))))
  (stop [this]
    (assoc this :config nil)))

(defn new-config
  ([] (map->Config {}))
  ([overrides] (map->Config {:config (merge (load-config) overrides)})))
