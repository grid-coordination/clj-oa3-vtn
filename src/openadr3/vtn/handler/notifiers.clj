(ns openadr3.vtn.handler.notifiers
  "GET /notifiers handler.

  The response is built from per-port :notifiers config, which specifies
  which notification bindings this port advertises. Example config:

    {:notifiers {:MQTT {:authentication {:method \"ANONYMOUS\"}}}}

  MQTT broker URL and serialization are filled in automatically from
  the top-level config. Set :notifiers to nil or omit :WEBHOOK to
  suppress webhook advertising on a port.")

(defn- build-mqtt-binding
  "Build the MQTT notifier binding object from config.
  When public URL keys are set, those are advertised to clients instead
  of the internal :mqtt-broker-url. Supports mqtt://, mqtts://, ws://,
  wss:// schemes. This allows the VTN to publish internally (e.g. via
  Cloud Map) while advertising public-facing NLB URLs to subscribers."
  [config mqtt-opts]
  (let [uris (or (not-empty (filterv some?
                                     [(:mqtt-public-url config)
                                      (:mqtt-public-url-tls config)
                                      (:mqtt-public-url-ws config)
                                      (:mqtt-public-url-wss config)]))
                 [(:mqtt-broker-url config)])]
    {:URIS uris
     :serialization "JSON"
     :authentication (or (:authentication mqtt-opts)
                         {:method "ANONYMOUS"})}))

(defn list-all
  "GET /notifiers — list all notifier bindings supported by this port.

  port-notifiers is a map describing which notifiers to advertise:
    {:MQTT {...}  :WEBHOOK true}
  If nil, defaults to {:MQTT {} :WEBHOOK true} for backward compatibility."
  [config port-notifiers]
  (let [notifiers (or port-notifiers {:MQTT {} :WEBHOOK true})]
    (fn [_request]
      (let [body (cond-> {;; WEBHOOK is required by the OA3 spec schema.
                         ;; Set to false when not supported (see VTN-t1i).
                          :WEBHOOK (boolean (:WEBHOOK notifiers))}
                   (:MQTT notifiers)
                   (assoc :MQTT (build-mqtt-binding config (:MQTT notifiers))))]
        {:status 200 :body body}))))
