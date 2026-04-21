(ns openadr3.vtn.handler.docs
  "API documentation handlers: filtered OpenAPI spec and Scalar UI."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [org.yaml.snakeyaml Yaml]))

(defn- java->clj
  "Recursively convert Java collections to Clojure equivalents."
  [x]
  (cond
    (instance? java.util.Map x)  (into {} (map (fn [[k v]] [k (java->clj v)])) x)
    (instance? java.util.List x) (mapv java->clj x)
    :else x))

(defn- load-spec
  "Load the OpenAPI YAML spec from classpath as a Clojure map."
  []
  (let [yaml (Yaml.)]
    (java->clj (.load yaml (slurp (io/resource "openadr3.yaml"))))))

(defn- filter-spec
  "Remove paths/methods from the spec that aren't in the handler-map.
   handler-map keys are [method path] pairs like [:get \"/programs\"]."
  [spec handler-map]
  (let [routed? (set (map (fn [[k _]] [(name (first k)) (second k)])
                          handler-map))]
    (update spec "paths"
            (fn [paths]
              (into {}
                    (for [[path methods] paths
                          :let [kept (into {}
                                           (for [[method op] methods
                                                 :when (routed? [method path])]
                                             [method op]))]
                          :when (seq kept)]
                      [path kept]))))))

(def ^:private default-tags
  [{"name" "programs"
    "description" "List available programs. Each program represents a rate schedule × location combination (e.g. `EELEC-013532223` for PG&E residential on a specific circuit, or `MOER-PGE` for GHG emissions in the PG&E region). Start here to find the program relevant to you."}
   {"name" "events"
    "description" "Fetch pricing and emissions data. Each event covers one day with 24 hourly intervals. Query by `programID` to get events for a specific program. Intervals contain either `PRICE` (USD/kWh) or `GHG` (g CO2/kWh) payloads."}
   {"name" "notifiers"
    "description" "Discover the MQTT broker for real-time push notifications. Returns broker URLs and authentication details."}
   {"name" "MQTT_notifier"
    "description" "MQTT topic discovery. Find the exact topic strings to subscribe to for a specific program's event notifications."}
   {"name" "Auth"
    "description" "Authentication endpoints. No authentication is currently required — all endpoints are read-only and publicly accessible."}])

(defn- brand-spec
  "Add server metadata to the spec for a branded docs page."
  [spec config]
  (let [ctx (or (:context-path config) "")]
    (-> spec
        (assoc-in ["info" "title"]
                  (or (:docs-title config) "OpenADR 3 VTN API"))
        (assoc-in ["info" "description"]
                  (or (:docs-description config)
                      "OpenADR 3.1.0 Virtual Top Node — read-only API for programs, events, and notifications."))
        (assoc "servers" [{"url" ctx}])
        (assoc "tags" (or (:docs-tags config) default-tags)))))

(defn openapi-yaml
  "Handler: serve the filtered OpenAPI spec as YAML."
  [handler-map config]
  (let [spec (-> (load-spec) (filter-spec handler-map) (brand-spec config))
        yaml (Yaml.)]
    (fn [_request]
      {:status 200
       :headers {"content-type" "application/x-yaml; charset=utf-8"}
       :body (.dump yaml spec)})))

(defn openapi-json
  "Handler: serve the filtered OpenAPI spec as JSON."
  [handler-map config]
  (let [spec (-> (load-spec) (filter-spec handler-map) (brand-spec config))]
    (fn [_request]
      {:status 200
       :headers {"content-type" "application/json; charset=utf-8"}
       :body (json/write-str spec)})))

(def ^:private docs-html
  "Minimal HTML page that loads Scalar API reference from CDN."
  "<!DOCTYPE html>
<html>
<head>
  <title>API Reference</title>
  <meta charset=\"utf-8\" />
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
</head>
<body>
  <script id=\"api-reference\" data-url=\"./openapi.json\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/@scalar/api-reference\"></script>
</body>
</html>")

(defn docs-page
  "Handler: serve the Scalar API reference HTML page."
  []
  (fn [_request]
    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body docs-html}))
