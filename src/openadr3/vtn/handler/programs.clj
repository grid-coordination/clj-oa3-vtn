(ns openadr3.vtn.handler.programs
  "Program CRUD handlers."
  (:require [openadr3.vtn.storage :as store]
            [openadr3.vtn.handler.common :as common]))

(defn search-all
  "GET /programs — search all programs with optional targets, skip, limit."
  [storage]
  (fn [request]
    (let [params (:query-params request)
          opts (merge (common/parse-pagination params)
                      (when-let [t (:targets params)] {:targets t}))]
      {:status 200
       :body (store/list-programs storage opts)})))

(defn create
  "POST /programs — create a new program."
  [storage]
  (fn [request]
    (try
      (let [body (:body request)
            program (common/add-metadata body "PROGRAM")
            created (store/create-program storage program)]
        {:status 201
         :body created})
      (catch clojure.lang.ExceptionInfo e
        (if (= :conflict (:type (ex-data e)))
          (common/conflict (.getMessage e))
          (throw e))))))

(defn get-by-id
  "GET /programs/{programID} — fetch a program by ID."
  [storage]
  (fn [request]
    (let [id (get-in request [:path-params :programID])]
      (if-let [program (store/get-program storage id)]
        {:status 200 :body program}
        (common/not-found "Program" id)))))

(defn update-by-id
  "PUT /programs/{programID} — update a program."
  [storage]
  (fn [request]
    (let [id   (get-in request [:path-params :programID])
          body (:body request)]
      (if-let [existing (store/get-program storage id)]
        (let [updated (common/touch-metadata existing body)
              stored  (store/update-program storage id updated)]
          {:status 200 :body stored})
        (common/not-found "Program" id)))))

(defn delete-by-id
  "DELETE /programs/{programID} — delete a program."
  [storage]
  (fn [request]
    (let [id (get-in request [:path-params :programID])]
      (if-let [deleted (store/delete-program storage id)]
        {:status 200 :body deleted}
        (common/not-found "Program" id)))))
