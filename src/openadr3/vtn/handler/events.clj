(ns openadr3.vtn.handler.events
  "Event CRUD handlers."
  (:require [openadr3.vtn.storage :as store]
            [openadr3.vtn.handler.common :as common]
            [openadr3.vtn.notifier :as notifier]))

(defn search-all
  "GET /events — search all events with optional programID, targets, skip, limit."
  [storage]
  (fn [request]
    (let [params (:query-params request)
          gp   (partial common/get-param params)
          opts (merge (common/parse-pagination params)
                      (when-let [pid (gp :programID)] {:programID pid})
                      (when-let [t (gp :targets)] {:targets t}))]
      {:status 200
       :body (store/list-events storage opts)})))

(defn create
  "POST /events — create a new event.
   Validates that programID references an existing program."
  [storage notifier-component]
  (fn [request]
    (let [body      (:body request)
          program-id (:programID body)]
      (if (and program-id (nil? (store/get-program storage program-id)))
        (common/bad-request (str "Program " program-id " not found"))
        (let [event   (common/add-metadata body "EVENT")
              created (store/create-event storage event)]
          (notifier/notify! notifier-component "EVENT" "CREATE" created)
          {:status 201
           :body created})))))

(defn get-by-id
  "GET /events/{eventID} — fetch an event by ID."
  [storage]
  (fn [request]
    (let [id (get-in request [:path-params :eventID])]
      (if-let [event (store/get-event storage id)]
        {:status 200 :body event}
        (common/not-found "Event" id)))))

(defn update-by-id
  "PUT /events/{eventID} — update an event."
  [storage notifier-component]
  (fn [request]
    (let [id   (get-in request [:path-params :eventID])
          body (:body request)]
      (if-let [existing (store/get-event storage id)]
        (let [updated (common/touch-metadata existing body)
              stored  (store/update-event storage id updated)]
          (notifier/notify! notifier-component "EVENT" "UPDATE" stored)
          {:status 200 :body stored})
        (common/not-found "Event" id)))))

(defn delete-by-id
  "DELETE /events/{eventID} — delete an event."
  [storage notifier-component]
  (fn [request]
    (let [id (get-in request [:path-params :eventID])]
      (if-let [deleted (store/delete-event storage id)]
        (do (notifier/notify! notifier-component "EVENT" "DELETE" deleted)
            {:status 200 :body deleted})
        (common/not-found "Event" id)))))
