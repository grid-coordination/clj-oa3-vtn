(ns openadr3.vtn.schema
  "VTN-side entity coercion and notification payload construction.

  Leverages clj-oa3's openadr3.entities for raw→coerced entity coercion.
  Provides additional helpers for building outbound notification payloads
  (the reverse direction: coerced/stored objects → wire-format notifications)."
  (:require [openadr3.entities :as entities]
            [openadr3.entities.schema :as schema]
            [openadr3.vtn.time :as time]
            [malli.core :as m]))

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
