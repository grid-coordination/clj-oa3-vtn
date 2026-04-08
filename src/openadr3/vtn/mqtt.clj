(ns openadr3.vtn.mqtt
  "MQTT publisher component using machine_head (Paho)."
  (:require [clojurewerkz.machine-head.client :as mh]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]))

(defn- normalize-broker-uri
  "Translate mqtt:// and mqtts:// to Paho-compatible tcp:// and ssl://."
  [uri]
  (let [u    (java.net.URI. uri)
        host (.getHost u)
        port (.getPort u)]
    (case (.getScheme u)
      "mqtt"  (str "tcp://" host ":" (if (pos? port) port 1883))
      "mqtts" (str "ssl://" host ":" (if (pos? port) port 8883))
      "tcp"   (str "tcp://" host ":" (if (pos? port) port 1883))
      "ssl"   (str "ssl://" host ":" (if (pos? port) port 8883))
      uri)))

(defrecord MqttPublisher [config client-atom]
  component/Lifecycle
  (start [this]
    (let [cfg      (:config config)
          url      (:mqtt-broker-url cfg)
          paho-url (normalize-broker-uri url)]
      (log/info "Connecting to MQTT broker" {:url url})
      (try
        (let [client (mh/connect paho-url {})]
          (log/info "MQTT connected" {:url url})
          (assoc this :client-atom (atom client)))
        (catch Exception e
          (log/warn e "Failed to connect to MQTT broker — notifications disabled"
                    {:url url})
          (assoc this :client-atom (atom nil))))))

  (stop [this]
    (when-let [client @client-atom]
      (try
        (when (mh/connected? client)
          (mh/disconnect-and-close client)
          (log/info "MQTT disconnected"))
        (catch Exception e
          (log/warn e "Error disconnecting MQTT"))))
    (assoc this :client-atom nil)))

(defn publish!
  "Publish a message to an MQTT topic.

  payload is a Clojure map that will be JSON-encoded.
  Options via config:
    :mqtt-retained — whether messages are retained (default false)

  Uses QoS 1 (at least once delivery)."
  [publisher topic payload]
  (when-let [client (some-> publisher :client-atom deref)]
    (when (mh/connected? client)
      (let [json-str (json/write-str payload)
            retained (get-in publisher [:config :config :mqtt-retained] false)]
        (mh/publish client topic json-str 1 retained)
        (log/debug "MQTT published" {:topic topic :retained retained})))))

(defn connected?
  "Returns true if the publisher has an active MQTT connection."
  [publisher]
  (boolean (some-> publisher :client-atom deref mh/connected?)))

(defn new-mqtt-publisher
  "Create an MqttPublisher component. Depends on :config."
  []
  (map->MqttPublisher {}))
