(ns openadr3.vtn.notifier
  "Notifier component: dispatches C/U/D notifications to MQTT topics.

  When a handler performs a successful create/update/delete, it calls
  (notify! notifier \"PROGRAM\" \"CREATE\" object). The notifier publishes
  to the appropriate MQTT topics."
  (:require [com.brunobonacci.mulog :as mu]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.mqtt :as mqtt]
            [openadr3.vtn.schema :as schema]))

(def ^:private topic-prefix "OpenADR/3.1.0")

(defn- object-type->collection
  "Map object type string to its collection name in topic paths."
  [object-type]
  (case object-type
    "PROGRAM"      "programs"
    "EVENT"        "events"
    "SUBSCRIPTION" "subscriptions"
    "VEN"          "vens"
    "RESOURCE"     "resources"
    "REPORT"       "reports"))

(defn- operation->topic-segment
  "Map operation string to topic segment."
  [operation]
  (.toLowerCase ^String operation))

(defn- global-topic
  "Build the global topic for an object type + operation.
   e.g. OpenADR/3.1.0/programs/create"
  [object-type operation]
  (str topic-prefix "/" (object-type->collection object-type)
       "/" (operation->topic-segment operation)))

(defn- scoped-topics
  "Build scoped topics based on the object and its type.

  For events: publish to programs/{programID}/events/{operation}
  For subscriptions: publish to programs/{programID} scoped if programID present"
  [object-type operation object]
  (case object-type
    "EVENT"
    (when-let [pid (:programID object)]
      [(str topic-prefix "/programs/" pid "/events/"
            (operation->topic-segment operation))])

    "PROGRAM"
    (when (not= "CREATE" operation)
      [(str topic-prefix "/programs/" (:id object) "/"
            (operation->topic-segment operation))])

    ;; Other types: no additional scoped topics in Phase 1
    nil))

(defrecord Notifier [mqtt-publisher]
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

(defn notify!
  "Dispatch a notification for a C/U/D operation.

  object-type — \"PROGRAM\", \"EVENT\", \"SUBSCRIPTION\"
  operation   — \"CREATE\", \"UPDATE\", \"DELETE\"
  object      — the raw stored object (with full metadata)

  Publishes to:
    1. Global topic (e.g. programs/create)
    2. Scoped topics (e.g. programs/{programID}/events/create)"
  [notifier object-type operation object]
  (let [publisher (:mqtt-publisher notifier)
        payload   (schema/notification-payload object-type operation object)
        g-topic   (global-topic object-type operation)
        s-topics  (scoped-topics object-type operation object)]
    ;; Publish to global topic
    (mqtt/publish! publisher g-topic payload)
    (mu/log ::notified :topic g-topic :operation operation :object-type object-type)
    ;; Publish to scoped topics
    (doseq [topic s-topics]
      (mqtt/publish! publisher topic payload)
      (mu/log ::notified-scoped :topic topic))))

(defn new-notifier
  "Create a Notifier component. Depends on :mqtt-publisher."
  []
  (map->Notifier {}))
