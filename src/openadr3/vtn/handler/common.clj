(ns openadr3.vtn.handler.common
  "Shared handler utilities: ID generation, metadata, body coercion,
  pagination, search-window construction, error responses.

  The VTN's storage layer is canonically `ZonedDateTime` / `Duration`.
  Wire-format request bodies arrive with string datetimes (parsed JSON);
  the `coerce-*-body` helpers here normalise them to the storage canon
  before reaching `add-metadata` and the storage protocol."
  (:require [openadr3.vtn.time :as time]
            [com.brunobonacci.mulog :as mu])
  (:import [java.util UUID]))

;; --- ID generation ---

(defn new-id
  "Generate a new object ID (random UUID string)."
  []
  (str (UUID/randomUUID)))

;; --- Body coercion (wire strings → storage canon) ---

(defn- coerce-interval-period
  "Parse string :start to ZonedDateTime and string :duration / :randomizeStart
   to Duration. Idempotent — already-parsed values pass through."
  [ip]
  (when ip
    (cond-> ip
      (some? (:start ip))          (update :start time/parse-zdt-maybe)
      (some? (:duration ip))       (update :duration time/parse-duration-maybe)
      (some? (:randomizeStart ip)) (update :randomizeStart time/parse-duration-maybe))))

(defn- coerce-interval [iv]
  (cond-> iv
    (:intervalPeriod iv) (update :intervalPeriod coerce-interval-period)))

(defn coerce-event-body
  "Parse string datetime/duration fields in an EVENT request body to the
   storage canon (ZonedDateTime / Duration). Idempotent."
  [body]
  (cond-> body
    (:intervalPeriod body) (update :intervalPeriod coerce-interval-period)
    (:intervals body)      (update :intervals (fn [is] (mapv coerce-interval is)))))

(defn coerce-program-body
  "Parse string datetime/duration fields in a PROGRAM request body."
  [body]
  (cond-> body
    (:intervalPeriod body) (update :intervalPeriod coerce-interval-period)))

;; --- Object metadata ---

(defn add-metadata
  "Add objectMetadata fields to a request body.
   Sets id, createdDateTime, modificationDateTime (both ZonedDateTime),
   and objectType."
  [body object-type]
  (let [now (time/now-zdt)]
    (assoc body
           :id (new-id)
           :createdDateTime now
           :modificationDateTime now
           :objectType object-type)))

(defn touch-metadata
  "Merge update body into stored object, preserving metadata fields.
   The update body overrides stored fields, but id, createdDateTime, and
   objectType are always preserved from stored. modificationDateTime is
   set to now (ZonedDateTime)."
  [stored updated]
  (-> (merge stored updated)
      (assoc :id (:id stored)
             :createdDateTime (:createdDateTime stored)
             :modificationDateTime (time/now-zdt)
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

;; --- Event search window ---
;;
;; Two search modes, both zone-neutral:
;;
;; 1. Caller supplied dateStart/dateEnd → :date-start / :date-end
;;    (ZonedDateTimes). Storage filters events whose intervalPeriod.start
;;    falls within [date-start, date-end] (status-quo BETWEEN semantics on
;;    the eventStart GSI).
;;
;; 2. No range supplied → :active-from / :active-until
;;    (ZonedDateTimes, defaulting to [now, now + 2d]). Storage filters
;;    events whose [start, start+duration] overlaps this window — works
;;    in any deployment zone, no UTC midnight assumption.

(def ^:private default-window-days 2)

(defn event-search-window
  "Build a search-window opts map for /events listing.

   When dateStart and/or dateEnd are present in the query, returns a map
   with :date-start / :date-end as ZonedDateTimes (BETWEEN semantics).
   Otherwise returns a map with :active-from / :active-until set to
   [now, now + 2 days] for zone-neutral overlap semantics."
  [query-params]
  (let [ds (get-param query-params :dateStart)
        de (get-param query-params :dateEnd)]
    (if (or ds de)
      (cond-> {}
        ds (assoc :date-start (time/parse-zdt-maybe ds))
        de (assoc :date-end   (time/parse-zdt-maybe de)))
      (let [now    (time/now-zdt)
            until  (time/plus-days now default-window-days)]
        (mu/log ::default-event-window :active-from now :active-until until)
        {:active-from now :active-until until}))))

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
