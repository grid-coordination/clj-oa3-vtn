(ns openadr3.vtn.schema
  "VTN-side entity coercion and notification payload construction.

  Leverages clj-oa3's openadr3.entities for raw→coerced entity coercion.
  Provides additional helpers for building outbound notification payloads
  (the reverse direction: coerced/stored objects → wire-format notifications)."
  (:require [openadr3.entities :as entities]
            [openadr3.entities.schema :as schema]
            [openadr3.vtn.time :as time]
            [malli.core :as m]
            [malli.error :as me]))

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
;; Wire-format Malli schemas for stored entities
;;
;; These schemas describe the camelCase keyword maps that the VTN stores
;; internally (post add-metadata, pre-DynamoDB). They enforce required fields
;; including those extracted as DynamoDB GSI attributes.
;; ---------------------------------------------------------------------------

(def ^:private iso-datetime-re
  "Regex for ISO 8601 / RFC 3339 datetime strings."
  #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}")

(def ^:private ISODatetime
  [:and :string [:re iso-datetime-re]])

(def ^:private ObjectMetadata
  "Fields added by handler/common/add-metadata to every stored entity."
  [[:id :string]
   [:createdDateTime ISODatetime]          ;; GSI: sort key for listings
   [:modificationDateTime ISODatetime]])

(def WireProgram
  "Malli schema for a stored program (wire-format, keyword keys).
   GSI fields: programName (programName-index SK)."
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
  "Malli schema for a stored event (wire-format, keyword keys).
   GSI fields: programID (programID-index, programID-eventStart-index),
               intervalPeriod.start → eventStart (objectType-eventStart-index,
               programID-eventStart-index)."
  (into [:map {:closed false}]
        (concat ObjectMetadata
                [[:objectType [:= "EVENT"]]
                 [:programID :string]                    ;; GSI: programID-index PK,
                                                         ;;      programID-eventStart-index PK
                 [:eventName {:optional true} :string]
                 [:priority {:optional true} :int]
                 [:intervalPeriod                         ;; GSI: .start → eventStart
                  [:map {:closed false}
                   [:start ISODatetime]                   ;; GSI: objectType-eventStart-index SK,
                                                          ;;      programID-eventStart-index SK
                   [:duration {:optional true} :string]
                   [:randomizeStart {:optional true} :string]]]
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
  "Malli schema for a stored subscription (wire-format, keyword keys).
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
  "Validate a stored entity against its wire-format schema.
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
    object      — the raw camelCase object map (as stored)

  Returns a map matching the OpenADR notification schema:
    {:objectType \"PROGRAM\"
     :operation  \"CREATE\"
     :object     {... the full object with timestamps ...}}

  All timestamps in the object are already RFC 3339 (set by handler/common)."
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

(defn coerce-stored
  "Coerce a stored raw object (which already has objectType metadata)
   into a namespaced entity for VTN internal logic.

   The stored object is a camelCase map with :objectType set by add-metadata.
   Returns a namespaced entity with :openadr/raw metadata."
  [stored-object]
  (entities/coerce stored-object))

(defn coerce-stored-programs
  "Coerce a sequence of stored program maps."
  [programs]
  (mapv entities/->program programs))

(defn coerce-stored-events
  "Coerce a sequence of stored event maps."
  [events]
  (mapv entities/->event events))

(defn coerce-stored-subscriptions
  "Coerce a sequence of stored subscription maps."
  [subscriptions]
  (mapv entities/->subscription subscriptions))
