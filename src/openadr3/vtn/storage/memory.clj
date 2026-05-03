(ns openadr3.vtn.storage.memory
  "Atom-backed storage implementation with optional file persistence via duratom.

  Holds entities canonically: datetime fields are `ZonedDateTime`,
  duration fields are `Duration`. The handler layer coerces incoming
  request bodies to this shape before reaching storage."
  (:require [com.stuartsierra.component :as component]
            [com.brunobonacci.mulog :as mu]
            [duratom.core :as duratom]
            [openadr3.vtn.storage :as storage])
  (:import [java.time Duration ZonedDateTime]))

(def ^:private empty-store {:programs {} :events {} :subscriptions {}})

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

(defn- zdt<= [^ZonedDateTime a ^ZonedDateTime b]
  (not (.isAfter a b)))

(defn- zdt>= [^ZonedDateTime a ^ZonedDateTime b]
  (not (.isBefore a b)))

(defn- in-explicit-range?
  "BETWEEN semantics: event's intervalPeriod.start is in [date-start, date-end].
   Events without intervalPeriod pass through. Either bound may be nil."
  [event date-start date-end]
  (let [es (get-in event [:intervalPeriod :start])]
    (or (nil? es)
        (and (or (nil? date-start) (zdt>= es date-start))
             (or (nil? date-end)   (zdt<= es date-end))))))

(defn- overlaps-window?
  "Overlap semantics: event's [start, start+duration] overlaps
   [active-from, active-until]. Events without intervalPeriod.start
   pass through (no time data to filter on). When duration is nil,
   the event is treated as instantaneous (end = start)."
  [event ^ZonedDateTime active-from ^ZonedDateTime active-until]
  (let [start    (get-in event [:intervalPeriod :start])
        duration (get-in event [:intervalPeriod :duration])]
    (or (nil? start)
        (let [^ZonedDateTime end (if duration
                                   (.plus start ^Duration duration)
                                   start)]
          (and (zdt<= start active-until)
               (zdt>= end   active-from))))))

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

(defn- make-state
  "Create the storage atom — either a plain atom or a file-backed duratom."
  [config]
  (if-let [path (:storage-file-path config)]
    (do (mu/log ::file-backed :path path)
        (duratom/duratom :local-file
                         :file-path path
                         :init empty-store))
    (do (mu/log ::in-memory)
        (atom empty-store))))

(defrecord AtomStorage [config state]
  component/Lifecycle
  (start [this]
    (if state
      this
      (assoc this :state (make-state (:config config)))))
  (stop [this]
    ;; Don't destroy the duratom — preserve the file for next startup.
    ;; duratom/destroy deletes the backing store.
    (assoc this :state nil))

  storage/VtnStorage

  ;; Programs
  (list-programs [_ opts]
    (filter-and-paginate
     (:programs @state)
     (fn [p]
       (and (if-let [pname (:programName opts)]
              (= pname (:programName p))
              true)
            (match-targets? p (:targets opts))))
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
    (let [{:keys [date-start date-end active-from active-until]} opts
          window-pred (cond
                        (or active-from active-until)
                        (fn [e] (overlaps-window? e active-from active-until))

                        (or date-start date-end)
                        (fn [e] (in-explicit-range? e date-start date-end))

                        :else (constantly true))]
      (filter-and-paginate
       (:events @state)
       (fn [e]
         (and (if-let [pid (:programID opts)]
                (= pid (:programID e))
                true)
              (window-pred e)
              (match-targets? e (:targets opts))))
       opts)))

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
  "Create an AtomStorage component. Depends on :config.
   When config has :storage-file-path, uses a file-backed duratom.
   Otherwise uses a plain in-memory atom."
  []
  (map->AtomStorage {}))
