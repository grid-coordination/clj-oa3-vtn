(ns openadr3.vtn.handler.notifiers
  "GET /notifiers handler.")

(defn list-all
  "GET /notifiers — list all notifier bindings supported by the server."
  [config]
  (fn [_request]
    (let [broker-url (:mqtt-broker-url config)]
      {:status 200
       :body {:WEBHOOK true
              :MQTT {:URIS [broker-url]
                     :serialization "JSON"
                     :authentication {:method "ANONYMOUS"}}}})))
