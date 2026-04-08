(ns user
  "REPL development helpers."
  (:require [com.stuartsierra.component :as component]
            [openadr3.vtn.system :as system]))

(def sys nil)

(defn start
  ([] (start {}))
  ([overrides]
   (alter-var-root #'sys
                   (fn [s]
                     (when s (component/stop s))
                     (component/start (system/system-map overrides))))))

(defn stop []
  (alter-var-root #'sys
                  (fn [s]
                    (when s (component/stop s))
                    nil)))

(defn reset
  ([] (reset {}))
  ([overrides]
   (stop)
   (start overrides)))
