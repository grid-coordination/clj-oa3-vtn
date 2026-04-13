(ns openadr3.vtn.handler.common
  "Shared handler utilities: ID generation, metadata, pagination, error responses."
  (:require [openadr3.vtn.time :as time]
            [com.brunobonacci.mulog :as mu])
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
  "Merge update body into stored object, preserving metadata fields.
   The update body overrides stored fields, but id, createdDateTime,
   objectType are always preserved from stored. modificationDateTime
   is set to now."
  [stored updated]
  (-> (merge stored updated)
      (assoc :id (:id stored)
             :createdDateTime (:createdDateTime stored)
             :modificationDateTime (time/now-rfc3339)
             :objectType (:objectType stored))))

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

(defn- ->int
  "Coerce a value to int. Handles strings, integers, and nil."
  [v]
  (cond
    (nil? v) nil
    (integer? v) v
    (string? v) (parse-long v)
    :else nil))

(defn get-param
  "Get a query param by keyword, falling back to string key.
   Legba uses string keys for query params."
  [params k]
  (or (get params k) (get params (name k))))

(defn parse-pagination
  "Extract skip/limit from query params, coercing to integers.
   Handles both keyword and string keys (Legba uses string keys)."
  [query-params]
  (let [skip  (->int (get-param query-params :skip))
        limit (->int (get-param query-params :limit))]
    (cond-> {}
      skip  (assoc :skip skip)
      limit (assoc :limit (min limit 50)))))

;; --- Event date range ---

(defn event-date-range
  "Extract explicit dateStart/dateEnd from query params, or default to
   today-start → tomorrow-end (UTC). Returns [date-start date-end]."
  [query-params]
  (let [ds (get-param query-params :dateStart)
        de (get-param query-params :dateEnd)]
    (if (or ds de)
      [ds de]
      (let [default-ds (time/today-start)
            default-de (time/tomorrow-end)]
        (mu/log ::default-event-date-range :date-start default-ds :date-end default-de)
        [default-ds default-de]))))

;; --- Error responses (RFC 9457 Problem Details) ---

(defn not-found
  "404 Not Found response."
  [resource-type id]
  {:status 404
   :body {:type "about:blank"
          :title "Not Found"
          :status 404
          :detail (str resource-type " " id " not found")}})

(defn bad-request
  "400 Bad Request response."
  [detail]
  {:status 400
   :body {:type "about:blank"
          :title "Bad Request"
          :status 400
          :detail detail}})

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
