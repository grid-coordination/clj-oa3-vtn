(ns openadr3.vtn.handler.events-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as store]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.handler.common :as common]
            [openadr3.vtn.handler.events :as events]))

(def ^:dynamic *storage* nil)
(def ^:dynamic *notifier* nil)

(defrecord StubNotifier []
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

(defn storage-fixture [f]
  (binding [*storage* (component/start (mem/new-atom-storage))
            *notifier* (->StubNotifier)]
    (try (f)
         (finally (component/stop *storage*)))))

(use-fixtures :each storage-fixture)

(defn- invoke [handler-fn & [request-overrides]]
  (let [request (merge {:query-params {} :path-params {} :body {}} request-overrides)]
    (try
      ((handler-fn *storage* *notifier*) request)
      (catch clojure.lang.ArityException _
        ((handler-fn *storage*) request)))))

(deftest create-and-get-test
  (let [prog (store/create-program *storage* (common/add-metadata {:programName "p1"} "PROGRAM"))
        resp (invoke events/create {:body {:programID (:id prog) :eventName "e1"}})]

    (testing "create returns 201"
      (is (= 201 (:status resp)))
      (is (= "EVENT" (get-in resp [:body :objectType]))))

    (testing "get by id"
      (let [id (get-in resp [:body :id])
            get-resp (invoke events/get-by-id {:path-params {:eventID id}})]
        (is (= 200 (:status get-resp)))
        (is (= (:id prog) (get-in get-resp [:body :programID])))))))

(deftest search-with-programID-filter-test
  (let [p1 (store/create-program *storage* (common/add-metadata {:programName "p1"} "PROGRAM"))
        p2 (store/create-program *storage* (common/add-metadata {:programName "p2"} "PROGRAM"))]
    (invoke events/create {:body {:programID (:id p1) :eventName "e1"}})
    (invoke events/create {:body {:programID (:id p1) :eventName "e2"}})
    (invoke events/create {:body {:programID (:id p2) :eventName "e3"}})

    (testing "filter by programID"
      (let [resp (invoke events/search-all {:query-params {:programID (:id p1)}})]
        (is (= 2 (count (:body resp))))))

    (testing "no filter returns all"
      (is (= 3 (count (:body (invoke events/search-all))))))))

(deftest update-and-delete-test
  (let [resp (invoke events/create {:body {:programID "p1" :eventName "orig"}})
        id (get-in resp [:body :id])]

    (testing "update"
      (let [upd (invoke events/update-by-id
                        {:path-params {:eventID id}
                         :body {:programID "p1" :eventName "changed" :priority 1}})]
        (is (= 200 (:status upd)))
        (is (= "changed" (get-in upd [:body :eventName])))))

    (testing "delete"
      (is (= 200 (:status (invoke events/delete-by-id {:path-params {:eventID id}}))))
      (is (= 404 (:status (invoke events/get-by-id {:path-params {:eventID id}})))))))
