(ns openadr3.vtn.storage.dynamo
  "DynamoDB-backed VtnStorage implementation using Cognitect aws-api.

  Single-table design:
    PK: objectType (S) — PROGRAM, EVENT, SUBSCRIPTION
    SK: id (S) — UUID

  GSIs:
    programName-index: PK=objectType, SK=programName
    programID-index:   PK=programID, SK=id"
  (:require [cognitect.aws.client.api :as aws]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
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
  (cond-> {:objectType {:S (:objectType obj)}
           :id         {:S (:id obj)}
           :data       {:S (json/write-str obj)}}
    (:programName obj)  (assoc :programName {:S (:programName obj)})
    (:programID obj)    (assoc :programID {:S (:programID obj)})
    (:clientName obj)   (assoc :clientName {:S (:clientName obj)})
    (:createdDateTime obj) (assoc :createdDateTime {:S (:createdDateTime obj)})))

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

(defn- query-by-type
  "Query all items of a given objectType, with optional pagination."
  [client table object-type {:keys [skip limit]}]
  (let [skip  (or skip 0)
        limit (or limit 50)
        resp  (aws/invoke client {:op :Query
                                  :request {:TableName table
                                            :KeyConditionExpression "objectType = :ot"
                                            :ExpressionAttributeValues {":ot" {:S object-type}}}})]
    (->> (:Items resp)
         (mapv item->obj)
         (sort-by :createdDateTime)
         (drop skip)
         (take limit)
         vec)))

(defn- query-by-index
  "Query a GSI with a partition key condition."
  [client table index-name pk-attr pk-value {:keys [skip limit]}]
  (let [skip  (or skip 0)
        limit (or limit 50)
        resp  (aws/invoke client {:op :Query
                                  :request {:TableName table
                                            :IndexName index-name
                                            :KeyConditionExpression (str pk-attr " = :pk")
                                            :ExpressionAttributeValues {":pk" {:S pk-value}}}})]
    (->> (:Items resp)
         (mapv item->obj)
         (sort-by :createdDateTime)
         (drop skip)
         (take limit)
         vec)))

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
;; Table creation (for dev/testing)
;; ---------------------------------------------------------------------------

(defn ensure-table!
  "Create the DynamoDB table and GSIs if they don't exist.
   Intended for local development (DynamoDB Local) and testing."
  [client table]
  (let [resp (aws/invoke client {:op :DescribeTable
                                 :request {:TableName table}})]
    (when (:cognitect.anomalies/category resp)
      (log/info "Creating DynamoDB table" table)
      (aws/invoke client
                  {:op :CreateTable
                   :request {:TableName table
                             :KeySchema [{:AttributeName "objectType" :KeyType "HASH"}
                                         {:AttributeName "id" :KeyType "RANGE"}]
                             :AttributeDefinitions [{:AttributeName "objectType" :AttributeType "S"}
                                                    {:AttributeName "id" :AttributeType "S"}
                                                    {:AttributeName "programName" :AttributeType "S"}
                                                    {:AttributeName "programID" :AttributeType "S"}]
                             :GlobalSecondaryIndexes
                             [{:IndexName "programName-index"
                               :KeySchema [{:AttributeName "objectType" :KeyType "HASH"}
                                           {:AttributeName "programName" :KeyType "RANGE"}]
                               :Projection {:ProjectionType "ALL"}}
                              {:IndexName "programID-index"
                               :KeySchema [{:AttributeName "programID" :KeyType "HASH"}
                                           {:AttributeName "id" :KeyType "RANGE"}]
                               :Projection {:ProjectionType "ALL"}}]
                             :BillingMode "PAY_PER_REQUEST"}}))))

;; ---------------------------------------------------------------------------
;; Component + VtnStorage implementation
;; ---------------------------------------------------------------------------

(defrecord DynamoStorage [config client table]
  component/Lifecycle
  (start [this]
    (if client
      this
      (let [cfg    (:config config)
            region (or (:dynamodb-region cfg) "us-west-2")
            tbl    (or (:dynamodb-table cfg) "openadr3")
            c      (ddb-client region)]
        (log/info "Storage: DynamoDB" {:table tbl :region region})
        (when (:dynamodb-ensure-table cfg)
          (ensure-table! c tbl))
        (assoc this :client c :table tbl))))

  (stop [this]
    (assoc this :client nil :table nil))

  storage/VtnStorage

  ;; Programs
  (list-programs [_ opts]
    (query-by-type client table "PROGRAM" opts))

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
        program)))

  (update-program [_ id program]
    (when (get-item client table "PROGRAM" id)
      (put-item! client table program)
      program))

  (delete-program [_ id]
    (delete-item! client table "PROGRAM" id))

  ;; Events
  (list-events [_ opts]
    (if-let [pid (:programID opts)]
      (query-by-index client table "programID-index" "programID" pid opts)
      (query-by-type client table "EVENT" opts)))

  (get-event [_ id]
    (get-item client table "EVENT" id))

  (create-event [_ event]
    (let [resp (put-item! client table event)]
      (when (:cognitect.anomalies/category resp)
        (throw (ex-info "DynamoDB PutItem failed" resp)))
      event))

  (update-event [_ id event]
    (when (get-item client table "EVENT" id)
      (put-item! client table event)
      event))

  (delete-event [_ id]
    (delete-item! client table "EVENT" id))

  ;; Subscriptions
  (list-subscriptions [_ opts]
    ;; No dedicated GSI for subscriptions — scan by type and filter in memory
    (let [all (query-by-type client table "SUBSCRIPTION"
                             {:skip 0 :limit 10000})]
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
