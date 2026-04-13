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

  Caching:
    Programs: cached with long TTL (default 1 hour) — rarely change
    Events:   cached per-page with short TTL (default 5 min)
              keyed by (programID, date-range, skip, limit)
    Caches invalidated on any mutation (create/update/delete)"
  (:require [cognitect.aws.client.api :as aws]
            [clojure.core.memoize :as memo]
            [clojure.data.json :as json]
            [com.brunobonacci.mulog :as mu]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as storage]))

;; ---------------------------------------------------------------------------
;; DynamoDB helpers
;; ---------------------------------------------------------------------------

(defn- ddb-client
  "Create a DynamoDB client for the given region."
  [region]
  (aws/client {:api :dynamodb
               :region (keyword region)}))

(defn- ->item
  "Convert a Clojure map to a DynamoDB item (with attribute type wrappers).
   Stores the full object as a JSON string in the 'data' attribute,
   plus top-level indexed fields as native DynamoDB attributes."
  [obj]
  (let [event-start (get-in obj [:intervalPeriod :start])]
    (cond-> {:objectType {:S (:objectType obj)}
             :id         {:S (:id obj)}
             :data       {:S (json/write-str obj)}}
      (:programName obj)     (assoc :programName {:S (:programName obj)})
      (:programID obj)       (assoc :programID {:S (:programID obj)})
      (:clientName obj)      (assoc :clientName {:S (:clientName obj)})
      (:createdDateTime obj) (assoc :createdDateTime {:S (:createdDateTime obj)})
      event-start            (assoc :eventStart {:S event-start}))))

(defn- item->obj
  "Convert a DynamoDB item back to a Clojure map by parsing the 'data' JSON."
  [item]
  (when-let [data (get-in item [:data :S])]
    (json/read-str data :key-fn keyword)))

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

(defn- query-events-page-raw
  "Query a page of events using eventStart GSIs for efficient date-range queries.
   Falls back to legacy GSI/table query (capped) if eventStart GSI unavailable."
  [client table program-id date-start date-end skip limit]
  (let [fetch-count (+ skip limit)
        request
        (cond
          ;; Per-program with date range → programID-eventStart-index
          (and program-id date-start date-end)
          {:TableName table
           :IndexName "programID-eventStart-index"
           :KeyConditionExpression "programID = :pid AND eventStart BETWEEN :ds AND :de"
           :ExpressionAttributeValues {":pid" {:S program-id}
                                       ":ds"  {:S date-start}
                                       ":de"  {:S date-end}}
           :ScanIndexForward true}

          ;; Per-program, no date range → existing programID-index (capped)
          program-id
          {:TableName table
           :IndexName "programID-index"
           :KeyConditionExpression "programID = :pk"
           :ExpressionAttributeValues {":pk" {:S program-id}}
           :ScanIndexForward false}

          ;; All events with date range → objectType-eventStart-index
          (and date-start date-end)
          {:TableName table
           :IndexName "objectType-eventStart-index"
           :KeyConditionExpression "objectType = :ot AND eventStart BETWEEN :ds AND :de"
           :ExpressionAttributeValues {":ot" {:S "EVENT"}
                                       ":ds" {:S date-start}
                                       ":de" {:S date-end}}
           :ScanIndexForward true}

          ;; All events, no filter → main table (capped, never load all 30K)
          :else
          {:TableName table
           :KeyConditionExpression "objectType = :ot"
           :ExpressionAttributeValues {":ot" {:S "EVENT"}}
           :ScanIndexForward false})

        resp (aws/invoke client {:op :Query :request (assoc request :Limit fetch-count)})]
    ;; Check for GSI not found — fall back to capped main table query
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
      ;; GSI query succeeded — may still need pagination across DynamoDB pages
      (let [items  (:Items resp [])
            lek    (:LastEvaluatedKey resp)
            needed (- fetch-count (count items))
            all-items (if (and (pos? needed) lek)
                        (into items
                              (query-limited-pages
                               client (assoc request :ExclusiveStartKey lek) needed))
                        items)]
        (->> all-items
             (mapv item->obj)
             (drop skip)
             vec)))))

(defn- paginate
  "Apply skip/limit to a collection."
  [coll {:keys [skip limit]}]
  (let [skip  (or skip 0)
        limit (or limit 50)]
    (->> coll (drop skip) (take limit) vec)))

(defn- find-program-by-name
  "Check if a program with the given name exists using the programName-index GSI."
  [client table name]
  (let [resp (aws/invoke client {:op :Query
                                 :request {:TableName table
                                           :IndexName "programName-index"
                                           :KeyConditionExpression "objectType = :ot AND programName = :pn"
                                           :ExpressionAttributeValues {":ot" {:S "PROGRAM"}
                                                                       ":pn" {:S name}}
                                           :Limit 1}})]
    (first (mapv item->obj (:Items resp)))))

;; ---------------------------------------------------------------------------
;; Cache construction
;; ---------------------------------------------------------------------------

(def ^:private default-program-ttl-ms  (* 60 60 1000))   ;; 1 hour
(def ^:private default-event-ttl-ms    (* 5 60 1000))    ;; 5 minutes

(defn- make-caches
  "Create memoized query functions with TTL caches.
   Events are cached per-page keyed by (programID, date-range, skip, limit)
   instead of caching all events in a single entry."
  [client table cfg]
  (let [prog-ttl  (or (:cache-program-ttl-ms cfg) default-program-ttl-ms)
        event-ttl (or (:cache-event-ttl-ms cfg) default-event-ttl-ms)]
    {:programs-fn
     (memo/ttl (fn [_table] (query-by-type-raw client table "PROGRAM"))
               :ttl/threshold prog-ttl)

     :events-page-fn
     (memo/ttl (fn [_table pid ds de skip limit]
                 (query-events-page-raw client table pid ds de skip limit))
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

  ;; Programs — cached
  (list-programs [_ opts]
    (paginate ((:programs-fn caches) table) opts))

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

  ;; Events — cached per-page with date-range filtering via eventStart GSIs
  (list-events [_ opts]
    (let [skip  (or (:skip opts) 0)
          limit (or (:limit opts) 50)
          pid   (:programID opts)
          ds    (:date-start opts)
          de    (:date-end opts)]
      ((:events-page-fn caches) table pid ds de skip limit)))

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
