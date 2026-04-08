(ns openadr3.vtn.storage.memory
  "Atom-backed in-memory storage implementation."
  (:require [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as storage]))

(defn- match-targets?
  "Check if an object's targets overlap with the filter targets.
   If filter-targets is nil/empty, matches everything."
  [object filter-targets]
  (or (empty? filter-targets)
      (let [obj-targets (set (map (fn [t] [(:type t) (first (:values t))])
                                  (:targets object)))]
        (some (fn [ft]
                (obj-targets [(:type ft) (first (:values ft))]))
              filter-targets))))

(defn- filter-and-paginate
  "Filter a collection of objects by predicate, then apply skip/limit."
  [objects pred {:keys [skip limit]}]
  (let [skip  (or skip 0)
        limit (or limit 50)
        filtered (filter pred (vals objects))]
    (->> filtered
         (sort-by :createdDateTime)
         (drop skip)
         (take limit)
         vec)))

(defrecord AtomStorage [state]
  component/Lifecycle
  (start [this]
    (if state
      this
      (assoc this :state (atom {:programs {}
                                :events {}
                                :subscriptions {}}))))
  (stop [this]
    (assoc this :state nil))

  storage/VtnStorage

  ;; Programs
  (list-programs [_ opts]
    (filter-and-paginate
     (:programs @state)
     (fn [p] (match-targets? p (:targets opts)))
     opts))

  (get-program [_ id]
    (get-in @state [:programs id]))

  (create-program [_ program]
    (let [id   (:id program)
          name (:programName program)]
      (when name
        (let [existing (some #(when (= name (:programName %)) %) (vals (:programs @state)))]
          (when existing
            (throw (ex-info "Duplicate programName"
                            {:type :conflict
                             :detail (str "Program with name '" name "' already exists")})))))
      (swap! state assoc-in [:programs id] program)
      program))

  (update-program [_ id program]
    (when (get-in @state [:programs id])
      (swap! state assoc-in [:programs id] program)
      program))

  (delete-program [_ id]
    (let [existing (get-in @state [:programs id])]
      (when existing
        (swap! state update :programs dissoc id)
        existing)))

  ;; Events
  (list-events [_ opts]
    (filter-and-paginate
     (:events @state)
     (fn [e]
       (and (if-let [pid (:programID opts)]
              (= pid (:programID e))
              true)
            (match-targets? e (:targets opts))))
     opts))

  (get-event [_ id]
    (get-in @state [:events id]))

  (create-event [_ event]
    (let [id (:id event)]
      (swap! state assoc-in [:events id] event)
      event))

  (update-event [_ id event]
    (when (get-in @state [:events id])
      (swap! state assoc-in [:events id] event)
      event))

  (delete-event [_ id]
    (let [existing (get-in @state [:events id])]
      (when existing
        (swap! state update :events dissoc id)
        existing)))

  ;; Subscriptions
  (list-subscriptions [_ opts]
    (filter-and-paginate
     (:subscriptions @state)
     (fn [s]
       (and (if-let [pid (:programID opts)]
              (= pid (:programID s))
              true)
            (if-let [cn (:clientName opts)]
              (= cn (:clientName s))
              true)
            (match-targets? s (:targets opts))))
     opts))

  (get-subscription [_ id]
    (get-in @state [:subscriptions id]))

  (create-subscription [_ sub]
    (let [id (:id sub)]
      (swap! state assoc-in [:subscriptions id] sub)
      sub))

  (update-subscription [_ id sub]
    (when (get-in @state [:subscriptions id])
      (swap! state assoc-in [:subscriptions id] sub)
      sub))

  (delete-subscription [_ id]
    (let [existing (get-in @state [:subscriptions id])]
      (when existing
        (swap! state update :subscriptions dissoc id)
        existing))))

(defn new-atom-storage
  "Create an AtomStorage component."
  []
  (map->AtomStorage {}))
