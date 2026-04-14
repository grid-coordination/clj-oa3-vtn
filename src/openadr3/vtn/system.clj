(ns openadr3.vtn.system
  "Component system map construction."
  (:require [com.stuartsierra.component :as component]
            [openadr3.vtn.config :as config]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.storage.dynamo :as dynamo]
            [openadr3.vtn.storage.validated :as validated]
            [openadr3.vtn.storage.notifying :as notifying]
            [openadr3.vtn.mqtt :as mqtt]
            [openadr3.vtn.notifier :as notifier]
            [openadr3.vtn.http :as http]
            [openadr3.vtn.handler :as handler]))

(defn- make-bl-handler
  "Build the BL port Ring handler."
  [storage config]
  (handler/make-routing-handler
   (handler/bl-handler-map storage config)))

(defn- make-ven-handler
  "Build the VEN port Ring handler."
  [storage config]
  (handler/make-routing-handler
   (handler/ven-handler-map storage config)))

(defn- storage-component
  "Create the storage component based on :storage-backend config.
   :memory (default) — in-memory atom, optionally file-backed via duratom
   :dynamodb — DynamoDB via Cognitect aws-api"
  [cfg]
  (case (keyword (or (:storage-backend cfg) :memory))
    :dynamodb (dynamo/new-dynamo-storage)
    (mem/new-atom-storage)))

(defn system-map
  "Construct the VTN component system map.
   Pass config overrides to customize (e.g. for testing).

   Storage chain: raw-storage → validated-storage → storage (notifying).
   All writes through :storage get validation + MQTT notifications."
  ([] (system-map {}))
  ([overrides]
   (let [cfg (merge (config/load-config) overrides)]
     (component/system-map
      :config            (config/new-config overrides)
      :raw-storage       (component/using (storage-component cfg) [:config])
      :validated-storage (component/using (validated/new-validating-storage) [:raw-storage])
      :mqtt-publisher    (component/using (mqtt/new-mqtt-publisher) [:config])
      :notifier          (component/using (notifier/new-notifier) [:mqtt-publisher])
      :storage           (component/using (notifying/new-notifying-storage)
                                          [:validated-storage :notifier])
      :http-server-bl    (component/using
                          (http/new-http-server (:bl-port cfg) :bl make-bl-handler)
                          [:config :storage])
      :http-server-ven   (component/using
                          (http/new-http-server (:ven-port cfg) :ven make-ven-handler)
                          [:config :storage])))))
