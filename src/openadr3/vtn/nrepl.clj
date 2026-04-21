(ns openadr3.vtn.nrepl
  "Optional nREPL server component for production inspection.
   Bind to localhost only — access via SSH tunnel or ECS execute-command."
  (:require [com.brunobonacci.mulog :as mu]
            [com.stuartsierra.component :as component]
            [nrepl.server :refer [start-server stop-server]]))

(defrecord NreplServer [port bind server]
  component/Lifecycle
  (start [this]
    (let [srv (start-server :port port :bind bind)]
      (mu/log ::started :port port :bind bind)
      (assoc this :server srv)))
  (stop [this]
    (when server
      (stop-server server)
      (mu/log ::stopped :port port))
    (assoc this :server nil)))

(defn new-server
  "Constructor for nREPL server component.
   Defaults: port 7888, bind localhost."
  ([] (new-server 7888 "localhost"))
  ([port] (new-server port "localhost"))
  ([port bind]
   (map->NreplServer {:port port
                      :bind (or bind "localhost")})))
