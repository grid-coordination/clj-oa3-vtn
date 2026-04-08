(ns openadr3.vtn.system
  "Component system map construction."
  (:require [com.stuartsierra.component :as component]
            [openadr3.vtn.config :as config]))

(defn system-map
  "Construct the VTN component system map.
   Pass config overrides to customize (e.g. for testing)."
  ([] (system-map {}))
  ([overrides]
   (component/system-map
    :config (config/new-config overrides)
     ;; :storage — VTN-jju
     ;; :mqtt-publisher — VTN-cao
     ;; :notifier — VTN-cao
     ;; :http-server-ven — VTN-khs
     ;; :http-server-bl — VTN-khs
    )))
