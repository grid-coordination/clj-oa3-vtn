(ns openadr3.vtn.storage.dynamo
  "DynamoDB-backed VtnStorage implementation using Cognitect aws-api.

  Single-table design:
    PK: objectType (S) — PROGRAM, EVENT, SUBSCRIPTION
    SK: id (S) — UUID

  GSIs:
    programName-index:           PK=objectType, SK=programName
    programID-index:             PK=programID, SK=id
    objectType-eventStart-index: PK=objectType, SK=eventStart (date-range queries)
    programID-eventStart-index:  PK=programID, SK=eventStart (per-program date-range)

  Wire-storage boundary
    Entities held in memory are canonically `ZonedDateTime` /
    `Duration`. At the DDB boundary:
      * `:data` attr is a JSON blob; the JSONWriter protocol extension in
        `vtn.time` serialises ZDT → canonical UTC Z and Duration → ISO.
      * `:eventStart` GSI sort key is canonical UTC Z (lex-orderable
        regardless of the wire offset on the input).
    On read, the JSON blob is parsed and known datetime fields are
    re-hydrated to ZDT / Duration before being returned.

  Caching:
    Programs: cached with long TTL (default 1 hour) — rarely change
    Events:   cached per-page with short TTL (default 5 min)
              keyed by canonical-string query args
    Caches invalidated on any mutation (create/update/delete)"
  (:require [cognitect.aws.client.api :as aws]
            [clojure.core.memoize :as memo]
            [clojure.data.json :as json]
            [com.brunobonacci.mulog :as mu]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as storage]
            [openadr3.vtn.time :as time])
  (:import [java.time ZonedDateTime]))

;; ---------------------------------------------------------------------------
;; ZDT/Duration hydration on read
;; ---------------------------------------------------------------------------

(defn- hydrate-interval-period [ip]
  (when ip
    (cond-> ip
      (string? (:start ip))          (update :start time/parse-zdt-maybe)
      (string? (:duration ip))       (update :duration time/parse-duration-maybe)
      (string? (:randomizeStart ip)) (update :randomizeStart time/parse-duration-maybe))))

(defn- hydrate-interval [iv]
  (cond-> iv
    (:intervalPeriod iv) (update :intervalPeriod hydrate-interval-period)))

(defn- hydrate-datetimes
  "Walk a parsed-JSON object and convert known datetime/duration fields
   from strings back to ZonedDateTime / Duration."
  [obj]
  (cond-> obj
    (string? (:createdDateTime obj))      (update :createdDateTime time/parse-zdt-maybe)
    (string? (:modificationDateTime obj)) (update :modificationDateTime time/parse-zdt-maybe)
    (:intervalPeriod obj)                 (update :intervalPeriod hydrate-interval-period)
    (:intervals obj)                      (update :intervals (fn [is] (mapv hydrate-interval is)))))

;; ---------------------------------------------------------------------------
;; DynamoDB helpers
;; ---------------------------------------------------------------------------

(defn- ddb-client
  "Create a DynamoDB client for the given region."
  [region]
  (aws/client {:api :dynamodb
               :region (keyword region)}))

(defn- event-start-key
  "Canonical UTC Z representation of an event's intervalPeriod.start, or nil
   if the event has no start. Independent of the input ZDT's zone, so the
   `:eventStart` GSI sort key is always lex-orderable regardless of the
   wire offset on the original input."
  [obj]
  (when-let [^ZonedDateTime zdt (get-in obj [:intervalPeriod :start])]
    (time/zdt->utc-z zdt)))

(defn- ->item
  "Convert a Clojure entity (ZDT-bearing) to a DynamoDB item.
   Stores the entity as a JSON string in the 'data' attribute (the
   JSONWriter protocol extension handles ZDT → UTC Z and Duration → ISO),
   plus top-level indexed fields as native DynamoDB attributes."
  [obj]
  (let [es (event-start-key obj)]
    (cond-> {:objectType {:S (:objectType obj)}
             :id         {:S (:id obj)}
             :data       {:S (json/write-str obj)}}
      (:programName obj)     (assoc :programName {:S (:programName obj)})
      (:programID obj)       (assoc :programID {:S (:programID obj)})
      (:clientName obj)      (assoc :clientName {:S (:clientName obj)})
      (:createdDateTime obj) (assoc :createdDateTime
                                    {:S (time/zdt->utc-z (:createdDateTime obj))})
      es                     (assoc :eventStart {:S es}))))

(defn- item->obj
  "Convert a DynamoDB item back to a Clojure entity by parsing the 'data'
   JSON and re-hydrating datetime/duration fields to ZDT / Duration."
  [item]
  (when-let [data (get-in item [:data :S])]
    (-> (json/read-str data :key-fn keyword)
        hydrate-datetimes)))

(defn- put-item! [client table obj]
  (aws/invoke client {:op :PutItem
                      :request {:TableName table
                                :Item (->item obj)}}))

(defn- get-item [client table object-type id]
  (let [resp (aws/invoke client {:op :GetItem
                                 :request {:TableName table
                                           :Key {:objectType {:S object-type}
                                                 :id {:S id}}}})]
    (item->obj (:Item resp))))

(defn- delete-item! [client table object-type id]
  (let [resp (aws/invoke client {:op :DeleteItem
                                 :request {:TableName table
                                           :Key {:objectType {:S object-type}
                                                 :id {:S id}}
                                           :ReturnValues "ALL_OLD"}})]
    (item->obj (:Attributes resp))))

(defn- query-all-pages
  "Execute a DynamoDB Query, following LastEvaluatedKey until all pages are fetched.
   Returns a vector of all raw Items across all pages."
  [client request]
  (loop [req request
         acc []]
    (let [resp  (aws/invoke client {:op :Query :request req})
          items (into acc (:Items resp))]
      (if-let [lek (:LastEvaluatedKey resp)]
        (recur (assoc req :ExclusiveStartKey lek) items)
        items))))

(defn- query-by-type-raw
  "Query all items of a given objectType, returning the full sorted list."
  [client table object-type]
  (->> (query-all-pages client
                        {:TableName table
                         :KeyConditionExpression "objectType = :ot"
                         :ExpressionAttributeValues {":ot" {:S object-type}}})
       (mapv item->obj)
       (sort-by :createdDateTime)
       vec))

(defn- query-limited-pages
  "Execute a DynamoDB Query following LastEvaluatedKey, but stop after max-items.
   Returns a vector of raw DynamoDB Items."
  [client request max-items]
  (loop [req request
         acc []
         remaining max-items]
    (if (<= remaining 0)
      acc
      (let [resp      (aws/invoke client {:op :Query :request (assoc req :Limit remaining)})
            new-items (:Items resp [])
            items     (into acc new-items)
            remaining' (- remaining (count new-items))]
        (if (and (pos? remaining')
                 (:LastEvaluatedKey resp))
          (recur (assoc req :ExclusiveStartKey (:LastEvaluatedKey resp))
                 items remaining')
          items)))))

;; ---------------------------------------------------------------------------
;; Event query routing
;;
;; Two filter modes:
;;   :explicit-range — caller supplied dateStart and/or dateEnd
;;     (BETWEEN on eventStart GSI). Status-quo semantics.
;;   :overlap-window — default; events whose [start, start+duration]
;;     overlaps [active-from, active-until]. Implemented as a half-bounded
;;     range scan on eventStart (eventStart <= active-until-z), then an
;;     in-code filter for end >= active-from.
;; ---------------------------------------------------------------------------

(defn- explicit-range-request
  "DDB query request for the BETWEEN-on-eventStart explicit-range mode."
  [table program-id ds-z de-z]
  (cond
    (and program-id ds-z de-z)
    {:TableName table
     :IndexName "programID-eventStart-index"
     :KeyConditionExpression "programID = :pid AND eventStart BETWEEN :ds AND :de"
     :ExpressionAttributeValues {":pid" {:S program-id}
                                 ":ds"  {:S ds-z}
                                 ":de"  {:S de-z}}
     :ScanIndexForward true}

    (and ds-z de-z)
    {:TableName table
     :IndexName "objectType-eventStart-index"
     :KeyConditionExpression "objectType = :ot AND eventStart BETWEEN :ds AND :de"
     :ExpressionAttributeValues {":ot" {:S "EVENT"}
                                 ":ds" {:S ds-z}
                                 ":de" {:S de-z}}
     :ScanIndexForward true}

    program-id
    {:TableName table
     :IndexName "programID-index"
     :KeyConditionExpression "programID = :pk"
     :ExpressionAttributeValues {":pk" {:S program-id}}
     :ScanIndexForward false}

    :else
    {:TableName table
     :KeyConditionExpression "objectType = :ot"
     :ExpressionAttributeValues {":ot" {:S "EVENT"}}
     :ScanIndexForward false}))

(defn- overlap-request
  "DDB query request for the overlap-window mode: eventStart <= active-until,
   in-code filter for end >= active-from after item materialisation."
  [table program-id until-z]
  (if program-id
    {:TableName table
     :IndexName "programID-eventStart-index"
     :KeyConditionExpression "programID = :pid AND eventStart <= :until"
     :ExpressionAttributeValues {":pid"   {:S program-id}
                                 ":until" {:S until-z}}
     :ScanIndexForward false}
    {:TableName table
     :IndexName "objectType-eventStart-index"
     :KeyConditionExpression "objectType = :ot AND eventStart <= :until"
     :ExpressionAttributeValues {":ot"    {:S "EVENT"}
                                 ":until" {:S until-z}}
     :ScanIndexForward false}))

(defn- overlaps?
  "True if the hydrated event's [start, start+duration] overlaps
   [from-zdt, until-zdt]. Events without a start pass through."
  [event from-zdt until-zdt]
  (let [start    (get-in event [:intervalPeriod :start])
        duration (get-in event [:intervalPeriod :duration])]
    (or (nil? start)
        (let [^ZonedDateTime end (if duration (.plus start duration) start)]
          (and (not (.isAfter ^ZonedDateTime start until-zdt))
               (not (.isBefore end from-zdt)))))))

(defn- query-events-page-raw
  "Query a page of events. Caller provides canonical-string args for cache
   stability. The two filter modes are mutually exclusive; both default
   to nil if absent.

   Falls back to a capped main-table scan if the GSI is unavailable
   (local DynamoDB / ensure-table not run)."
  [client table program-id mode ds-z de-z from-z until-z skip limit]
  (let [fetch-count (+ skip limit)
        request     (case mode
                      :overlap-window (overlap-request table program-id until-z)
                      :explicit-range (explicit-range-request table program-id ds-z de-z)
                      ;; no window — list-by-program or list-all
                      (explicit-range-request table program-id nil nil))
        resp        (aws/invoke client {:op :Query
                                        :request (assoc request :Limit fetch-count)})]
    (if (:cognitect.anomalies/category resp)
      (do (mu/log ::gsi-fallback :anomaly (:cognitect.anomalies/category resp)
                  :index (:IndexName request)
                  :message (:Message resp))
          (->> (query-limited-pages client
                                    (cond-> {:TableName table
                                             :KeyConditionExpression "objectType = :ot"
                                             :ExpressionAttributeValues {":ot" {:S "EVENT"}}
                                             :ScanIndexForward false}
                                      program-id
                                      (-> (assoc :IndexName "programID-index"
                                                 :KeyConditionExpression "programID = :pk"
                                                 :ExpressionAttributeValues {":pk" {:S program-id}})))
                                    fetch-count)
               (mapv item->obj)
               (drop skip)
               vec))
      (let [items     (:Items resp [])
            lek       (:LastEvaluatedKey resp)
            needed    (- fetch-count (count items))
            all-items (if (and (pos? needed) lek)
                        (into items (query-limited-pages
                                     client (assoc request :ExclusiveStartKey lek) needed))
                        items)
            hydrated  (mapv item->obj all-items)
            filtered  (case mode
                        :overlap-window
                        (let [from-zdt  (time/parse-zdt from-z)
                              until-zdt (time/parse-zdt until-z)]
                          (filterv #(overlaps? % from-zdt until-zdt) hydrated))
                        hydrated)]
        (->> filtered (drop skip) vec)))))

(defn- paginate
  "Apply skip/limit to a collection."
  [coll {:keys [skip limit]}]
  (let [skip  (or skip 0)
        limit (or limit 50)]
    (->> coll (drop skip) (take limit) vec)))

(defn- query-programs-by-name
  "Query the programName-index GSI for programs matching the given name.
   Returns a vector of program maps (programNames are unique, so 0 or 1)."
  [client table name]
  (let [resp (aws/invoke client {:op :Query
                                 :request {:TableName table
                                           :IndexName "programName-index"
                                           :KeyConditionExpression "objectType = :ot AND programName = :pn"
                                           :ExpressionAttributeValues {":ot" {:S "PROGRAM"}
                                                                       ":pn" {:S name}}
                                           :Limit 1}})]
    (mapv item->obj (:Items resp))))

(defn- find-program-by-name
  "Check if a program with the given name exists using the programName-index GSI."
  [client table name]
  (first (query-programs-by-name client table name)))

;; ---------------------------------------------------------------------------
;; Cache construction
;; ---------------------------------------------------------------------------

(def ^:private default-program-ttl-ms  (* 60 60 1000))   ;; 1 hour
(def ^:private default-event-ttl-ms    (* 5 60 1000))    ;; 5 minutes

(defn- make-caches
  "Create memoized query functions with TTL caches.
   Events are cached per-page keyed by canonical-string query args."
  [client table cfg]
  (let [prog-ttl  (or (:cache-program-ttl-ms cfg) default-program-ttl-ms)
        event-ttl (or (:cache-event-ttl-ms cfg) default-event-ttl-ms)]
    {:programs-fn
     (memo/ttl (fn [_table] (query-by-type-raw client table "PROGRAM"))
               :ttl/threshold prog-ttl)

     :events-page-fn
     (memo/ttl (fn [_table pid mode ds-z de-z from-z until-z skip limit]
                 (query-events-page-raw client table pid mode ds-z de-z from-z until-z skip limit))
               :ttl/threshold event-ttl)}))

(defn- invalidate-programs! [{:keys [programs-fn]}]
  (memo/memo-clear! programs-fn)
  (mu/log ::cache-invalidated :type "PROGRAM"))

(defn- invalidate-events! [{:keys [events-page-fn]}]
  (memo/memo-clear! events-page-fn)
  (mu/log ::cache-invalidated :type "EVENT"))

;; ---------------------------------------------------------------------------
;; Table creation (for dev/testing)
;; ---------------------------------------------------------------------------

(defn ensure-table!
  "Create the DynamoDB table and GSIs if they don't exist.
   Intended for local development (DynamoDB Local) and testing."
  [client table]
  (let [resp (aws/invoke client {:op :DescribeTable
                                 :request {:TableName table}})]
    (when (:cognitect.anomalies/category resp)
      (mu/log ::creating-table :table table)
      (aws/invoke client
                  {:op :CreateTable
                   :request {:TableName table
                             :KeySchema [{:AttributeName "objectType" :KeyType "HASH"}
                                         {:AttributeName "id" :KeyType "RANGE"}]
                             :AttributeDefinitions [{:AttributeName "objectType" :AttributeType "S"}
                                                    {:AttributeName "id" :AttributeType "S"}
                                                    {:AttributeName "programName" :AttributeType "S"}
                                                    {:AttributeName "programID" :AttributeType "S"}
                                                    {:AttributeName "eventStart" :AttributeType "S"}]
                             :GlobalSecondaryIndexes
                             [{:IndexName "programName-index"
                               :KeySchema [{:AttributeName "objectType" :KeyType "HASH"}
                                           {:AttributeName "programName" :KeyType "RANGE"}]
                               :Projection {:ProjectionType "ALL"}}
                              {:IndexName "programID-index"
                               :KeySchema [{:AttributeName "programID" :KeyType "HASH"}
                                           {:AttributeName "id" :KeyType "RANGE"}]
                               :Projection {:ProjectionType "ALL"}}
                              {:IndexName "objectType-eventStart-index"
                               :KeySchema [{:AttributeName "objectType" :KeyType "HASH"}
                                           {:AttributeName "eventStart" :KeyType "RANGE"}]
                               :Projection {:ProjectionType "ALL"}}
                              {:IndexName "programID-eventStart-index"
                               :KeySchema [{:AttributeName "programID" :KeyType "HASH"}
                                           {:AttributeName "eventStart" :KeyType "RANGE"}]
                               :Projection {:ProjectionType "ALL"}}]
                             :BillingMode "PAY_PER_REQUEST"}}))))

;; ---------------------------------------------------------------------------
;; Component + VtnStorage implementation
;; ---------------------------------------------------------------------------

(defrecord DynamoStorage [config client table caches]
  component/Lifecycle
  (start [this]
    (if client
      this
      (let [cfg    (:config config)
            region (or (:dynamodb-region cfg) "us-west-2")
            tbl    (or (:dynamodb-table cfg) "openadr3")
            c      (ddb-client region)]
        (mu/log ::started :table tbl :region region)
        (when (:dynamodb-ensure-table cfg)
          (ensure-table! c tbl))
        (assoc this
               :client c
               :table tbl
               :caches (make-caches c tbl cfg)))))

  (stop [this]
    (assoc this :client nil :table nil :caches nil))

  storage/VtnStorage

  ;; Programs — cached for unfiltered listing; programName lookups bypass
  ;; the cache and query the programName-index GSI directly so callers
  ;; (e.g. price-server's ensure-program!) get fresh results without
  ;; scanning the full collection.
  (list-programs [_ opts]
    (if-let [pname (:programName opts)]
      (paginate (query-programs-by-name client table pname) opts)
      (paginate ((:programs-fn caches) table) opts)))

  (get-program [_ id]
    (get-item client table "PROGRAM" id))

  (create-program [_ program]
    (let [name (:programName program)]
      (when name
        (when (find-program-by-name client table name)
          (throw (ex-info "Duplicate programName"
                          {:type :conflict
                           :detail (str "Program with name '" name "' already exists")}))))
      (let [resp (put-item! client table program)]
        (when (:cognitect.anomalies/category resp)
          (throw (ex-info "DynamoDB PutItem failed" resp)))
        (invalidate-programs! caches)
        program)))

  (update-program [_ id program]
    (when (get-item client table "PROGRAM" id)
      (put-item! client table program)
      (invalidate-programs! caches)
      program))

  (delete-program [_ id]
    (let [result (delete-item! client table "PROGRAM" id)]
      (when result (invalidate-programs! caches))
      result))

  ;; Events — cached per-page with date-range or overlap filtering.
  ;; Cache keys are canonical UTC Z strings so identical-instant queries
  ;; coalesce regardless of caller-side zone differences.
  (list-events [_ opts]
    (let [skip   (or (:skip opts) 0)
          limit  (or (:limit opts) 50)
          pid    (:programID opts)
          {:keys [date-start date-end active-from active-until]} opts
          mode   (cond
                   (or active-from active-until) :overlap-window
                   (or date-start date-end)      :explicit-range
                   :else                         nil)
          ds-z   (some-> date-start time/zdt->utc-z)
          de-z   (some-> date-end time/zdt->utc-z)
          from-z (some-> active-from time/zdt->utc-z)
          until-z (some-> active-until time/zdt->utc-z)]
      ((:events-page-fn caches) table pid mode ds-z de-z from-z until-z skip limit)))

  (get-event [_ id]
    (get-item client table "EVENT" id))

  (create-event [_ event]
    (let [resp (put-item! client table event)]
      (when (:cognitect.anomalies/category resp)
        (throw (ex-info "DynamoDB PutItem failed" resp)))
      (invalidate-events! caches)
      event))

  (update-event [_ id event]
    (when (get-item client table "EVENT" id)
      (put-item! client table event)
      (invalidate-events! caches)
      event))

  (delete-event [_ id]
    (let [result (delete-item! client table "EVENT" id)]
      (when result (invalidate-events! caches))
      result))

  ;; Subscriptions — not cached (low volume)
  (list-subscriptions [_ opts]
    (let [items (query-all-pages client
                                 {:TableName table
                                  :KeyConditionExpression "objectType = :ot"
                                  :ExpressionAttributeValues {":ot" {:S "SUBSCRIPTION"}}})
          all   (mapv item->obj items)]
      (->> all
           (filter (fn [s]
                     (and (if-let [pid (:programID opts)]
                            (= pid (:programID s))
                            true)
                          (if-let [cn (:clientName opts)]
                            (= cn (:clientName s))
                            true))))
           (sort-by :createdDateTime)
           (drop (or (:skip opts) 0))
           (take (or (:limit opts) 50))
           vec)))

  (get-subscription [_ id]
    (get-item client table "SUBSCRIPTION" id))

  (create-subscription [_ sub]
    (let [resp (put-item! client table sub)]
      (when (:cognitect.anomalies/category resp)
        (throw (ex-info "DynamoDB PutItem failed" resp)))
      sub))

  (update-subscription [_ id sub]
    (when (get-item client table "SUBSCRIPTION" id)
      (put-item! client table sub)
      sub))

  (delete-subscription [_ id]
    (delete-item! client table "SUBSCRIPTION" id)))

(defn new-dynamo-storage
  "Create a DynamoStorage component. Depends on :config."
  []
  (map->DynamoStorage {}))
