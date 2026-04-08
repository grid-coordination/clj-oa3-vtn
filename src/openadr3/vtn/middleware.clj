(ns openadr3.vtn.middleware
  "Ring middleware: context path stripping and request logging."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.data.json :as json]))

(defn wrap-context-path
  "Strip a context path prefix from the request URI before routing.
   For example, with context-path \"/openadr3/3.1.0\":
     /openadr3/3.1.0/programs → /programs"
  [handler context-path]
  (if (str/blank? context-path)
    handler
    (fn [request]
      (let [uri (:uri request)]
        (if (str/starts-with? uri context-path)
          (handler (assoc request :uri (subs uri (count context-path))))
          {:status 404
           :headers {"content-type" "application/json"}
           :body "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}"})))))

(defn wrap-json-response
  "Ensure response bodies that are maps or vectors are JSON-encoded.
   Legba handles this for most spec-matched routes, but some responses
   (e.g. via $ref'd response schemas) may pass through as raw maps."
  [handler]
  (fn [request]
    (let [resp (handler request)
          body (:body resp)]
      (if (or (map? body) (sequential? body))
        (-> resp
            (assoc :body (json/write-str body))
            (assoc-in [:headers "content-type"] "application/json"))
        resp))))

(defn wrap-request-logging
  "Log incoming requests at debug level."
  [handler]
  (fn [request]
    (log/debug (:request-method request) (:uri request))
    (let [resp (handler request)]
      (log/debug (:request-method request) (:uri request) "→" (:status resp))
      resp)))
