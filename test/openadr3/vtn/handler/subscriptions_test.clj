(ns openadr3.vtn.handler.subscriptions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.handler.subscriptions :as subs]))

(def ^:dynamic *storage* nil)

(defn storage-fixture [f]
  (binding [*storage* (component/start (mem/new-atom-storage))]
    (try (f)
         (finally (component/stop *storage*)))))

(use-fixtures :each storage-fixture)

(defn- invoke [handler-fn & [request-overrides]]
  (let [request (merge {:query-params {} :path-params {} :body {}} request-overrides)]
    ((handler-fn *storage*) request)))

(defn- sub-body [program-id]
  {:programID program-id
   :clientName "test-client"
   :objectOperations [{:objects ["EVENT"] :operations ["CREATE" "UPDATE"]}]})

(deftest create-and-get-test
  (let [resp (invoke subs/create {:body (sub-body "prog-1")})]

    (testing "create returns 201"
      (is (= 201 (:status resp)))
      (is (= "SUBSCRIPTION" (get-in resp [:body :objectType])))
      (is (string? (get-in resp [:body :id]))))

    (testing "get by id"
      (let [id (get-in resp [:body :id])
            get-resp (invoke subs/get-by-id {:path-params {:subscriptionID id}})]
        (is (= 200 (:status get-resp)))
        (is (= "test-client" (get-in get-resp [:body :clientName])))))))

(deftest search-filters-test
  (invoke subs/create {:body (sub-body "prog-1")})
  (invoke subs/create {:body (assoc (sub-body "prog-2") :clientName "other-client")})

  (testing "filter by programID"
    (let [resp (invoke subs/search-all {:query-params {:programID "prog-1"}})]
      (is (= 1 (count (:body resp))))))

  (testing "filter by clientName"
    (let [resp (invoke subs/search-all {:query-params {:clientName "other-client"}})]
      (is (= 1 (count (:body resp))))))

  (testing "no filter returns all"
    (is (= 2 (count (:body (invoke subs/search-all)))))))

(deftest update-and-delete-test
  (let [resp (invoke subs/create {:body (sub-body "prog-1")})
        id (get-in resp [:body :id])]

    (testing "update"
      (let [upd (invoke subs/update-by-id
                        {:path-params {:subscriptionID id}
                         :body (assoc (sub-body "prog-1") :clientName "new-name")})]
        (is (= 200 (:status upd)))
        (is (= "new-name" (get-in upd [:body :clientName])))))

    (testing "delete"
      (is (= 200 (:status (invoke subs/delete-by-id {:path-params {:subscriptionID id}}))))
      (is (= 404 (:status (invoke subs/get-by-id {:path-params {:subscriptionID id}})))))))

(deftest not-found-test
  (testing "get nonexistent"
    (is (= 404 (:status (invoke subs/get-by-id {:path-params {:subscriptionID "nope"}})))))
  (testing "update nonexistent"
    (is (= 404 (:status (invoke subs/update-by-id {:path-params {:subscriptionID "nope"} :body {}})))))
  (testing "delete nonexistent"
    (is (= 404 (:status (invoke subs/delete-by-id {:path-params {:subscriptionID "nope"}}))))))
