(ns openadr3.vtn.handler.subscriptions
  "Subscription CRUD handlers."
  (:require [openadr3.vtn.storage :as store]
            [openadr3.vtn.handler.common :as common]))

(defn search-all
  "GET /subscriptions — search subscriptions with optional programID, clientName, objects, skip, limit."
  [storage]
  (fn [request]
    (let [params (:query-params request)
          opts (merge (common/parse-pagination params)
                      (when-let [pid (:programID params)] {:programID pid})
                      (when-let [cn (:clientName params)] {:clientName cn})
                      (when-let [t (:targets params)] {:targets t}))]
      {:status 200
       :body (store/list-subscriptions storage opts)})))

(defn create
  "POST /subscriptions — create a new subscription."
  [storage]
  (fn [request]
    (let [body (:body request)
          sub  (common/add-metadata body "SUBSCRIPTION")
          created (store/create-subscription storage sub)]
      {:status 201
       :body created})))

(defn get-by-id
  "GET /subscriptions/{subscriptionID} — fetch a subscription by ID."
  [storage]
  (fn [request]
    (let [id (get-in request [:path-params :subscriptionID])]
      (if-let [sub (store/get-subscription storage id)]
        {:status 200 :body sub}
        (common/not-found "Subscription" id)))))

(defn update-by-id
  "PUT /subscriptions/{subscriptionID} — update a subscription."
  [storage]
  (fn [request]
    (let [id   (get-in request [:path-params :subscriptionID])
          body (:body request)]
      (if-let [existing (store/get-subscription storage id)]
        (let [updated (common/touch-metadata existing body)
              stored  (store/update-subscription storage id updated)]
          {:status 200 :body stored})
        (common/not-found "Subscription" id)))))

(defn delete-by-id
  "DELETE /subscriptions/{subscriptionID} — delete a subscription."
  [storage]
  (fn [request]
    (let [id (get-in request [:path-params :subscriptionID])]
      (if (store/delete-subscription storage id)
        {:status 200 :body {:id id}}
        (common/not-found "Subscription" id)))))
