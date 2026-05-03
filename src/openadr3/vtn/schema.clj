(ns openadr3.vtn.schema
  "VTN-side entity coercion and notification payload construction.

  Leverages clj-oa3's openadr3.entities for raw→coerced entity coercion.
  Provides additional helpers for building outbound notification payloads
  (the reverse direction: coerced/stored objects → wire-format notifications)."
  (:require [openadr3.entities :as entities]
            [openadr3.entities.schema :as schema]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.time Duration ZonedDateTime]))

;; ---------------------------------------------------------------------------
;; Re-exports from clj-oa3 for convenience within VTN code
;; ---------------------------------------------------------------------------

(def coerce
  "Coerce a raw camelCase API map to a namespaced entity.
   Dispatches on :objectType (\"PROGRAM\", \"EVENT\", etc.)."
  entities/coerce)

(def ->program entities/->program)
(def ->event entities/->event)
(def ->subscription entities/->subscription)

;; Malli schemas for coerced entities
(def Program schema/Program)
(def Event schema/Event)
(def Subscription schema/Subscription)
(def Notification schema/Notification)

;; ---------------------------------------------------------------------------
;; Storage-format Malli schemas
;;
;; These schemas describe the camelCase keyword maps that the VTN holds
;; internally. The VTN's storage canon is `ZonedDateTime` for datetime
;; fields and `Duration` for duration fields — wire strings have already
;; been coerced by handler/common before reaching ValidatingStorage.
;; ---------------------------------------------------------------------------

(def ^:private ZDT
  [:fn {:error/message "must be a ZonedDateTime"} #(instance? ZonedDateTime %)])

(def ^:private Dur
  [:fn {:error/message "must be a Duration"} #(instance? Duration %)])

(def ^:private ObjectMetadata
  "Fields added by handler/common/add-metadata to every stored entity."
  [[:id :string]
   [:createdDateTime ZDT]                  ;; GSI-projected; UTC-Z at the DDB boundary
   [:modificationDateTime ZDT]])

(def WireProgram
  "Malli schema for a stored program (storage canon, keyword keys, ZDT
   datetimes). GSI fields: programName (programName-index SK)."
  (into [:map {:closed false}]
        (concat ObjectMetadata
                [[:objectType [:= "PROGRAM"]]
                 [:programName :string]                  ;; GSI: programName-index SK
                 [:programLongName {:optional true} :string]
                 [:retailerName {:optional true} :string]
                 [:retailerLongName {:optional true} :string]
                 [:programType {:optional true} :string]
                 [:country {:optional true} :string]
                 [:principalSubdivision {:optional true} :string]
                 [:payloadDescriptors {:optional true} [:vector :map]]
                 [:targets {:optional true} [:vector :map]]
                 [:intervalPeriod {:optional true} :map]])))

(def WireEvent
  "Malli schema for a stored event (storage canon, keyword keys, ZDT/Duration
   in time fields). GSI fields: programID (programID-index, programID-eventStart-index),
   intervalPeriod.start → eventStart (objectType-eventStart-index,
   programID-eventStart-index)."
  (into [:map {:closed false}]
        (concat ObjectMetadata
                [[:objectType [:= "EVENT"]]
                 [:programID :string]                    ;; GSI: programID-index PK,
                                                         ;;      programID-eventStart-index PK
                 [:eventName {:optional true} :string]
                 [:priority {:optional true} :int]
                 [:intervalPeriod
                  [:map {:closed false}
                   [:start ZDT]                          ;; canonicalised to UTC Z for the
                                                         ;; eventStart GSI sort key on write
                   [:duration {:optional true} Dur]
                   [:randomizeStart {:optional true} Dur]]]
                 [:intervals {:optional true}
                  [:vector
                   [:map {:closed false}
                    [:id :int]
                    [:payloads {:optional true} [:vector :map]]
                    [:intervalPeriod {:optional true} :map]]]]
                 [:payloadDescriptors {:optional true} [:vector :map]]
                 [:reportDescriptors {:optional true} [:vector :map]]
                 [:targets {:optional true} [:vector :map]]])))

(def WireSubscription
  "Malli schema for a stored subscription (storage canon, keyword keys).
   GSI fields: clientName (query filter), programID (programID-index)."
  (into [:map {:closed false}]
        (concat ObjectMetadata
                [[:objectType [:= "SUBSCRIPTION"]]
                 [:clientName :string]
                 [:clientID :string]
                 [:programID {:optional true} :string]
                 [:objectOperations
                  [:vector
                   [:map {:closed false}
                    [:objects [:vector :string]]
                    [:operations [:vector :string]]
                    [:callbackUrl {:optional true} :string]
                    [:bearerToken {:optional true} :string]]]]
                 [:targets {:optional true} [:vector :map]]])))

(def ^:private wire-schemas
  {"PROGRAM"      WireProgram
   "EVENT"        WireEvent
   "SUBSCRIPTION" WireSubscription})

(defn validate-entity!
  "Validate a stored entity against its storage-canon schema.
   Throws ex-info with :type :validation-error and Malli explanation on failure.
   Returns the entity unchanged on success."
  [entity]
  (let [object-type (:objectType entity)]
    (if-let [schema (wire-schemas object-type)]
      (when-let [explanation (m/explain schema entity)]
        (throw (ex-info (str "Entity validation failed for " object-type)
                        {:type :validation-error
                         :object-type object-type
                         :explanation (me/humanize explanation)
                         :entity entity})))
      (throw (ex-info (str "Unknown objectType: " (pr-str object-type))
                      {:type :validation-error
                       :object-type object-type
                       :entity entity})))
    entity))

;; ---------------------------------------------------------------------------
;; Outbound notification payload construction
;; ---------------------------------------------------------------------------

(def operations
  "Valid OpenADR notification operations."
  #{"CREATE" "UPDATE" "DELETE"})

(def object-types
  "Valid OpenADR object types for notifications."
  #{"PROGRAM" "EVENT" "SUBSCRIPTION" "VEN" "RESOURCE" "REPORT"})

(defn notification-payload
  "Build a wire-format notification map suitable for MQTT publishing.

  Takes:
    object-type — string, e.g. \"PROGRAM\", \"EVENT\"
    operation   — string, e.g. \"CREATE\", \"UPDATE\", \"DELETE\"
    object      — the storage-canon entity (ZDT-bearing)

  Returns a map matching the OpenADR notification schema:
    {:objectType \"PROGRAM\"
     :operation  \"CREATE\"
     :object     {... entity with ZDT/Duration fields ...}}

  ZDTs and Durations are serialised to canonical UTC Z / ISO 8601 by the
  JSONWriter protocol extension when the payload is JSON-encoded for the
  wire."
  [object-type operation object]
  {:objectType object-type
   :operation operation
   :object object})

;; Malli schema for the outbound wire-format notification
(def NotificationPayload
  "Malli schema for an outbound notification (wire format, camelCase)."
  [:map
   [:objectType [:enum "PROGRAM" "EVENT" "SUBSCRIPTION" "VEN" "RESOURCE" "REPORT"]]
   [:operation [:enum "CREATE" "UPDATE" "DELETE"]]
   [:object :map]])

(defn validate-notification-payload
  "Validate an outbound notification payload. Returns nil on success,
   Malli explanation on failure."
  [payload]
  (m/explain NotificationPayload payload))

;; ---------------------------------------------------------------------------
;; Coerce stored objects for VTN internal logic
;; ---------------------------------------------------------------------------

(defn- zdt->wire [v]
  (if (instance? ZonedDateTime v)
    (.format ^ZonedDateTime v java.time.format.DateTimeFormatter/ISO_INSTANT)
    v))

(defn- dur->wire [v]
  (if (instance? Duration v) (.toString ^Duration v) v))

(defn- entity->raw
  "Produce a wire-shape (string-bearing) map from a storage-canon entity.
   Used as the input to clj-oa3 entities/coerce — that library expects
   strings on its input."
  [obj]
  (let [ip   (:intervalPeriod obj)
        ip*  (some-> ip
                     (cond->
                      (:start ip)          (update :start zdt->wire)
                      (:duration ip)       (update :duration dur->wire)
                      (:randomizeStart ip) (update :randomizeStart dur->wire)))
        ivs  (:intervals obj)
        ivs* (when ivs
               (mapv (fn [iv]
                       (cond-> iv
                         (:intervalPeriod iv)
                         (update :intervalPeriod
                                 (fn [x]
                                   (cond-> x
                                     (:start x)          (update :start zdt->wire)
                                     (:duration x)       (update :duration dur->wire)
                                     (:randomizeStart x) (update :randomizeStart dur->wire))))))
                     ivs))]
    (cond-> obj
      (:createdDateTime obj)      (update :createdDateTime zdt->wire)
      (:modificationDateTime obj) (update :modificationDateTime zdt->wire)
      ip                          (assoc :intervalPeriod ip*)
      ivs                         (assoc :intervals ivs*))))

(defn coerce-stored
  "Coerce a stored storage-canon object into a namespaced entity for VTN
   internal logic. Bridges from the VTN's ZDT/Duration shape to clj-oa3's
   string-bearing input form before delegating to entities/coerce."
  [stored-object]
  (entities/coerce (entity->raw stored-object)))

(defn coerce-stored-programs
  "Coerce a sequence of stored program maps."
  [programs]
  (mapv (comp entities/->program entity->raw) programs))

(defn coerce-stored-events
  "Coerce a sequence of stored event maps."
  [events]
  (mapv (comp entities/->event entity->raw) events))

(defn coerce-stored-subscriptions
  "Coerce a sequence of stored subscription maps."
  [subscriptions]
  (mapv (comp entities/->subscription entity->raw) subscriptions))
