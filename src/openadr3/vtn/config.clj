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
   :storage-backend :memory
   :ven-routes {:programs      :read-only
                :events        :read-only
                :subscriptions false
                :vens          false
                :resources     false
                :reports       false}})

(defn- config-source
  "Resolve the config source: external file path from system property
   or env var, falling back to classpath resource."
  []
  (or (when-let [path (or (System/getProperty "openadr3.config")
                          (System/getenv "CLJ_OA3_VTN_CONFIG"))]
        (let [f (io/file path)]
          (when (.exists f) f)))
      (io/resource "config.edn")))

(defn load-config
  "Load config.edn and merge with defaults.
   Config source resolution order:
     1. System property: openadr3.config
     2. Environment variable: CLJ_OA3_VTN_CONFIG
     3. Classpath resource: config.edn"
  []
  (let [source (config-source)
        file-config (when source
                      (edn/read-string (slurp source)))]
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
