(ns user
  "REPL development helpers."
  (:require [com.stuartsierra.component :as component]
            [com.brunobonacci.mulog :as mu]
            [openadr3.vtn.system :as system]))

;; Start mulog console publisher for dev
(mu/start-publisher! {:type :console})

(def sys nil)

(defn start
  "Start the VTN system. Pass overrides map to customize config."
  ([] (start {}))
  ([overrides]
   (alter-var-root #'sys
                   (fn [s]
                     (when s (component/stop s))
                     (let [s' (component/start (system/system-map overrides))
                           cfg (get-in s' [:config :config])]
                       (println)
                       (println "  OpenADR 3.1.0 VTN started")
                       (println "  ─────────────────────────────────")
                       (println "  BL  port:" (:bl-port cfg)
                                " " (:context-path cfg))
                       (println "  VEN port:" (:ven-port cfg)
                                " " (:context-path cfg))
                       (println "  MQTT:    " (:mqtt-broker-url cfg))
                       (println)
                       s')))))

(defn stop
  "Stop the VTN system and release all resources."
  []
  (alter-var-root #'sys
                  (fn [s]
                    (when s
                      (component/stop s)
                      (println "  VTN stopped"))
                    nil)))

(defn reset
  "Stop and restart with fresh state."
  ([] (reset {}))
  ([overrides]
   (stop)
   (start overrides)))

(defn status
  "Print the current system status."
  []
  (if-not sys
    (println "  VTN is not running")
    (let [cfg (get-in sys [:config :config])]
      (println)
      (println "  OpenADR 3.1.0 VTN status")
      (println "  ─────────────────────────────────")
      (println "  BL  server:" (if (get-in sys [:http-server-bl :server]) "UP" "DOWN")
               " port" (:bl-port cfg))
      (println "  VEN server:" (if (get-in sys [:http-server-ven :server]) "UP" "DOWN")
               " port" (:ven-port cfg))
      (println "  MQTT:      " (if (some-> sys :mqtt-publisher :client-atom deref)
                                 "connected" "disconnected")
               " " (:mqtt-broker-url cfg))
      (println "  Storage:   " (if (get-in sys [:storage :state])
                                 (let [s @(get-in sys [:storage :state])]
                                   (str (count (:programs s)) " programs, "
                                        (count (:events s)) " events, "
                                        (count (:subscriptions s)) " subscriptions"))
                                 "nil"))
      (println))))
