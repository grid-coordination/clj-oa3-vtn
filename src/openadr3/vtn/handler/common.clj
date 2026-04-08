(ns openadr3.vtn.handler.common
  "Shared handler utilities: ID generation, metadata, pagination, error responses."
  (:require [openadr3.vtn.time :as time])
  (:import [java.util UUID]))

;; --- ID generation ---

(defn new-id
  "Generate a new object ID (random UUID string)."
  []
  (str (UUID/randomUUID)))

;; --- Object metadata ---

(defn add-metadata
  "Add objectMetadata fields to a raw request body.
   Sets id, createdDateTime, modificationDateTime, and objectType."
  [body object-type]
  (let [now (time/now-rfc3339)]
    (assoc body
           :id (new-id)
           :createdDateTime now
           :modificationDateTime now
           :objectType object-type)))

(defn touch-metadata
  "Update modificationDateTime on an existing object.
   Preserves id, createdDateTime, and objectType from the stored version."
  [stored updated]
  (assoc updated
         :id (:id stored)
         :createdDateTime (:createdDateTime stored)
         :modificationDateTime (time/now-rfc3339)
         :objectType (:objectType stored)))

;; --- Pagination ---

(defn paginate
  "Apply skip/limit pagination to a collection.
   Defaults: skip=0, limit=50 (OpenADR max)."
  [coll {:keys [skip limit]}]
  (let [skip  (or skip 0)
        limit (or limit 50)]
    (->> coll
         (drop skip)
         (take limit)
         vec)))

(defn parse-pagination
  "Extract skip/limit from query params, coercing to integers."
  [query-params]
  (let [skip  (some-> (:skip query-params) parse-long)
        limit (some-> (:limit query-params) parse-long)]
    (cond-> {}
      skip  (assoc :skip skip)
      limit (assoc :limit (min limit 50)))))

;; --- Error responses (RFC 9457 Problem Details) ---

(defn not-found
  "404 Not Found response."
  [resource-type id]
  {:status 404
   :body {:type "about:blank"
          :title "Not Found"
          :status 404
          :detail (str resource-type " " id " not found")}})

(defn conflict
  "409 Conflict response."
  [detail]
  {:status 409
   :body {:type "about:blank"
          :title "Conflict"
          :status 409
          :detail detail}})

(defn not-implemented
  "501 Not Implemented response."
  []
  {:status 501
   :body {:type "about:blank"
          :title "Not Implemented"
          :status 501
          :detail "This endpoint is not implemented"}})
