(ns openadr3.vtn.middleware
  "Ring middleware: context path stripping, JSON response, and request logging."
  (:require [com.brunobonacci.mulog :as mu]
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
  "Ensure response bodies are JSON-encoded with proper Content-Type.
   Legba serializes matched route bodies to JSON strings but does not
   always set Content-Type. This middleware:
   - Encodes map/vector bodies to JSON strings
   - Sets Content-Type: application/json on all JSON responses"
  [handler]
  (fn [request]
    (let [resp (handler request)
          body (:body resp)]
      (cond
        ;; Map or vector body — encode to JSON and set content-type
        (or (map? body) (sequential? body))
        (-> resp
            (assoc :body (json/write-str body))
            (assoc-in [:headers "content-type"] "application/json"))

        ;; String body (Legba already serialized) — ensure content-type is set
        (string? body)
        (update resp :headers
                (fn [h] (if (get h "content-type") h
                            (assoc h "content-type" "application/json"))))

        :else resp))))

(defn- client-ip
  "Extract client IP from request, respecting X-Forwarded-For behind load balancers."
  [request]
  (or (some-> (get-in request [:headers "x-forwarded-for"])
              (str/split #",")
              first
              str/trim)
      (:remote-addr request)))

(defn wrap-request-logging
  "Log HTTP requests as structured mulog events with timing."
  [handler]
  (fn [request]
    (let [start (System/nanoTime)
          resp  (handler request)
          ms    (/ (- (System/nanoTime) start) 1e6)]
      (mu/log ::http-request
              :method (:request-method request)
              :uri (:uri request)
              :query-string (:query-string request)
              :status (:status resp)
              :duration-ms (Math/round ms)
              :remote-addr (client-ip request)
              :user-agent (get-in request [:headers "user-agent"]))
      resp)))
