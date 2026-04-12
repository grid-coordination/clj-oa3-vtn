(ns openadr3.vtn.http
  "HTTP server component wrapping Jetty."
  (:require [ring.adapter.jetty :as jetty]
            [com.brunobonacci.mulog :as mu]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.middleware :as mw]))

(defn- port-available?
  "Check if a TCP port is available for binding."
  [port]
  (try
    (let [ss (java.net.ServerSocket. port)]
      (.close ss)
      true)
    (catch java.net.BindException _ false)))

(defrecord HttpServer [port role handler-fn config storage notifier server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (do
        (when-not (port-available? port)
          (throw (ex-info (str "Port " port " is already in use")
                          {:port port :role role})))
        (let [cfg          (:config config)
              context-path (:context-path cfg)
              handler      (-> (handler-fn storage notifier cfg)
                               mw/wrap-json-response
                               (mw/wrap-context-path context-path)
                               mw/wrap-request-logging)
              srv          (jetty/run-jetty handler {:port port :join? false})]
          (mu/log ::started :role role :port port :context-path context-path)
          (assoc this :server srv)))))

  (stop [this]
    (when server
      (try
        (.stop server)
        (mu/log ::stopped :role role :port port)
        (catch Exception e
          (mu/log ::stop-error :role role :port port :exception e))))
    (assoc this :server nil)))

(defn new-http-server
  "Create an HttpServer component.
   port       — TCP port
   role       — :ven or :bl (for logging)
   handler-fn — (fn [storage notifier config] ring-handler)"
  [port role handler-fn]
  (map->HttpServer {:port port :role role :handler-fn handler-fn}))
