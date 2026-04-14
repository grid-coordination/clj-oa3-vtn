(ns openadr3.vtn.storage.validated
  "Validating storage decorator.
   Wraps any VtnStorage implementation and validates entities against
   wire-format Malli schemas on create and update operations.
   Delegates all reads and deletes unchanged.

   As a Component, depends on :raw-storage (the underlying VtnStorage impl).
   All existing system references to :storage get validation automatically."
  (:require [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as storage]
            [openadr3.vtn.schema :as schema]))

(defrecord ValidatingStorage [raw-storage]
  component/Lifecycle
  (start [this] this)
  (stop [this] this)

  storage/VtnStorage

  ;; Programs
  (list-programs [_ opts]
    (storage/list-programs raw-storage opts))

  (get-program [_ id]
    (storage/get-program raw-storage id))

  (create-program [_ program]
    (schema/validate-entity! program)
    (storage/create-program raw-storage program))

  (update-program [_ id program]
    (schema/validate-entity! program)
    (storage/update-program raw-storage id program))

  (delete-program [_ id]
    (storage/delete-program raw-storage id))

  ;; Events
  (list-events [_ opts]
    (storage/list-events raw-storage opts))

  (get-event [_ id]
    (storage/get-event raw-storage id))

  (create-event [_ event]
    (schema/validate-entity! event)
    (storage/create-event raw-storage event))

  (update-event [_ id event]
    (schema/validate-entity! event)
    (storage/update-event raw-storage id event))

  (delete-event [_ id]
    (storage/delete-event raw-storage id))

  ;; Subscriptions
  (list-subscriptions [_ opts]
    (storage/list-subscriptions raw-storage opts))

  (get-subscription [_ id]
    (storage/get-subscription raw-storage id))

  (create-subscription [_ sub]
    (schema/validate-entity! sub)
    (storage/create-subscription raw-storage sub))

  (update-subscription [_ id sub]
    (schema/validate-entity! sub)
    (storage/update-subscription raw-storage id sub))

  (delete-subscription [_ id]
    (storage/delete-subscription raw-storage id)))

(defn new-validating-storage
  "Create a ValidatingStorage component. Depends on :raw-storage."
  []
  (map->ValidatingStorage {}))

(defn wrap-validation
  "Wrap a VtnStorage implementation with schema validation (non-Component use).
   Returns a ValidatingStorage that validates on create/update."
  [storage]
  (->ValidatingStorage storage))
