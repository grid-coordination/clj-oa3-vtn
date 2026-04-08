(ns openadr3.vtn.storage
  "VTN storage protocol definition.")

(defprotocol VtnStorage
  "Protocol for VTN object persistence.
   All methods work with raw camelCase maps (the JSON wire format).
   Handlers add objectMetadata (id, timestamps, objectType) before storing."

  ;; Programs
  (list-programs  [store opts])
  (get-program    [store id])
  (create-program [store program])
  (update-program [store id program])
  (delete-program [store id])

  ;; Events
  (list-events  [store opts])
  (get-event    [store id])
  (create-event [store event])
  (update-event [store id event])
  (delete-event [store id])

  ;; Subscriptions
  (list-subscriptions  [store opts])
  (get-subscription    [store id])
  (create-subscription [store sub])
  (update-subscription [store id sub])
  (delete-subscription [store id]))
