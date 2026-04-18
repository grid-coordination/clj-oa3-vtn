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

(defn- wrap-docs
  "Middleware: intercept /docs and /openapi.json before context-path routing.
   docs-routes is a map of {uri-string ring-handler}."
  [handler docs-routes]
  (if (seq docs-routes)
    (fn [request]
      (if-let [docs-handler (get docs-routes (:uri request))]
        (docs-handler request)
        (handler request)))
    handler))

(defrecord HttpServer [port role handler-fn docs-fn config storage server]
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
              docs-routes  (when docs-fn (docs-fn storage cfg))
              handler      (-> (handler-fn storage cfg)
                               mw/wrap-json-response
                               (mw/wrap-context-path context-path)
                               (wrap-docs docs-routes)
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
   handler-fn — (fn [storage config] ring-handler)
   docs-fn    — optional (fn [storage config] {uri handler}) for API docs"
  ([port role handler-fn]
   (new-http-server port role handler-fn nil))
  ([port role handler-fn docs-fn]
   (map->HttpServer {:port port :role role :handler-fn handler-fn :docs-fn docs-fn})))
