(ns openadr3.vtn.handler.auth
  "Auth endpoint stubs: GET /auth/server, POST /auth/token."
  (:require [openadr3.vtn.handler.common :as common]))

(defn server-info
  "GET /auth/server — return OAuth2 server discovery info."
  [config]
  (fn [_request]
    (let [base-url (str "http://localhost:" (:bl-port config)
                        (:context-path config))]
      {:status 200
       :body {:tokenURL (str base-url "/auth/token")}})))

(defn fetch-token
  "POST /auth/token — not implemented (optional per spec)."
  []
  (fn [_request]
    (common/not-implemented)))
