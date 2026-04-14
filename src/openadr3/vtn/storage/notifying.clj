(ns openadr3.vtn.storage.notifying
  "Notifying storage decorator.
   Wraps any VtnStorage implementation and publishes MQTT notifications
   on create, update, and delete operations. Reads delegate unchanged.

   As a Component, depends on :validated-storage and :notifier."
  (:require [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as storage]
            [openadr3.vtn.notifier :as notifier]))

(defrecord NotifyingStorage [validated-storage notifier]
  component/Lifecycle
  (start [this] this)
  (stop [this] this)

  storage/VtnStorage

  ;; Programs
  (list-programs [_ opts]
    (storage/list-programs validated-storage opts))

  (get-program [_ id]
    (storage/get-program validated-storage id))

  (create-program [_ program]
    (let [created (storage/create-program validated-storage program)]
      (notifier/notify! notifier "PROGRAM" "CREATE" created)
      created))

  (update-program [_ id program]
    (let [updated (storage/update-program validated-storage id program)]
      (when updated
        (notifier/notify! notifier "PROGRAM" "UPDATE" updated))
      updated))

  (delete-program [_ id]
    (let [deleted (storage/delete-program validated-storage id)]
      (when deleted
        (notifier/notify! notifier "PROGRAM" "DELETE" deleted))
      deleted))

  ;; Events
  (list-events [_ opts]
    (storage/list-events validated-storage opts))

  (get-event [_ id]
    (storage/get-event validated-storage id))

  (create-event [_ event]
    (let [created (storage/create-event validated-storage event)]
      (notifier/notify! notifier "EVENT" "CREATE" created)
      created))

  (update-event [_ id event]
    (let [updated (storage/update-event validated-storage id event)]
      (when updated
        (notifier/notify! notifier "EVENT" "UPDATE" updated))
      updated))

  (delete-event [_ id]
    (let [deleted (storage/delete-event validated-storage id)]
      (when deleted
        (notifier/notify! notifier "EVENT" "DELETE" deleted))
      deleted))

  ;; Subscriptions
  (list-subscriptions [_ opts]
    (storage/list-subscriptions validated-storage opts))

  (get-subscription [_ id]
    (storage/get-subscription validated-storage id))

  (create-subscription [_ sub]
    (let [created (storage/create-subscription validated-storage sub)]
      (notifier/notify! notifier "SUBSCRIPTION" "CREATE" created)
      created))

  (update-subscription [_ id sub]
    (let [updated (storage/update-subscription validated-storage id sub)]
      (when updated
        (notifier/notify! notifier "SUBSCRIPTION" "UPDATE" updated))
      updated))

  (delete-subscription [_ id]
    (let [deleted (storage/delete-subscription validated-storage id)]
      (when deleted
        (notifier/notify! notifier "SUBSCRIPTION" "DELETE" deleted))
      deleted)))

(defn new-notifying-storage
  "Create a NotifyingStorage component.
   Depends on :validated-storage and :notifier."
  []
  (map->NotifyingStorage {}))
