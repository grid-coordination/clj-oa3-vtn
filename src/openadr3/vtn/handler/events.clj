(ns openadr3.vtn.handler.events
  "Event CRUD handlers."
  (:require [openadr3.vtn.storage :as store]
            [openadr3.vtn.handler.common :as common]))

(defn search-all
  "GET /events — search all events with optional programID, targets, skip, limit."
  [storage]
  (fn [request]
    (let [params (:query-params request)
          opts (merge (common/parse-pagination params)
                      (when-let [pid (:programID params)] {:programID pid})
                      (when-let [t (:targets params)] {:targets t}))]
      {:status 200
       :body (store/list-events storage opts)})))

(defn create
  "POST /events — create a new event."
  [storage]
  (fn [request]
    (let [body  (:body request)
          event (common/add-metadata body "EVENT")
          created (store/create-event storage event)]
      {:status 201
       :body created})))

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
  [storage]
  (fn [request]
    (let [id   (get-in request [:path-params :eventID])
          body (:body request)]
      (if-let [existing (store/get-event storage id)]
        (let [updated (common/touch-metadata existing body)
              stored  (store/update-event storage id updated)]
          {:status 200 :body stored})
        (common/not-found "Event" id)))))

(defn delete-by-id
  "DELETE /events/{eventID} — delete an event."
  [storage]
  (fn [request]
    (let [id (get-in request [:path-params :eventID])]
      (if (store/delete-event storage id)
        {:status 200 :body {:id id}}
        (common/not-found "Event" id)))))
