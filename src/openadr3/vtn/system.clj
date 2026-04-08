(ns openadr3.vtn.system
  "Component system map construction."
  (:require [com.stuartsierra.component :as component]
            [openadr3.vtn.config :as config]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.mqtt :as mqtt]
            [openadr3.vtn.notifier :as notifier]
            [openadr3.vtn.http :as http]
            [openadr3.vtn.handler :as handler]))

(defn- make-bl-handler
  "Build the BL port Ring handler."
  [storage notifier-component config]
  (handler/make-routing-handler
   (handler/bl-handler-map storage notifier-component config)))

(defn- make-ven-handler
  "Build the VEN port Ring handler."
  [storage notifier-component config]
  (handler/make-routing-handler
   (handler/ven-handler-map storage notifier-component config)))

(defn system-map
  "Construct the VTN component system map.
   Pass config overrides to customize (e.g. for testing)."
  ([] (system-map {}))
  ([overrides]
   (let [cfg (merge (config/load-config) overrides)]
     (component/system-map
      :config         (config/new-config overrides)
      :storage        (mem/new-atom-storage)
      :mqtt-publisher (component/using (mqtt/new-mqtt-publisher) [:config])
      :notifier       (component/using (notifier/new-notifier) [:mqtt-publisher])
      :http-server-bl (component/using
                       (http/new-http-server (:bl-port cfg) :bl make-bl-handler)
                       [:config :storage :notifier])
      :http-server-ven (component/using
                        (http/new-http-server (:ven-port cfg) :ven make-ven-handler)
                        [:config :storage :notifier])))))
