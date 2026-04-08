(ns openadr3.vtn.handler.programs-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.handler.programs :as programs]))

(def ^:dynamic *storage* nil)

;; A stub notifier that records notifications for assertions
(def ^:dynamic *notifications* nil)

(defrecord StubNotifier [notifications-atom]
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

(defn- stub-notifier []
  (->StubNotifier (atom [])))

(defn storage-fixture [f]
  (let [notifier (stub-notifier)]
    (binding [*storage* (component/start (mem/new-atom-storage))
              *notifications* notifier]
      (try (f)
           (finally (component/stop *storage*))))))

(use-fixtures :each storage-fixture)

;; Override notifier/notify! to record instead of publish
(defmethod clojure.core/print-method StubNotifier [_ _])

(defn- invoke
  "Invoke a handler. For read handlers (1-arg), pass storage only.
   For write handlers (2-arg), pass storage and notifier."
  [handler-fn & [request-overrides]]
  (let [request (merge {:query-params {} :path-params {} :body {}} request-overrides)]
    (try
      ((handler-fn *storage* *notifications*) request)
      (catch clojure.lang.ArityException _
        ((handler-fn *storage*) request)))))

(deftest search-all-test
  (testing "empty list"
    (let [resp (invoke programs/search-all)]
      (is (= 200 (:status resp)))
      (is (= [] (:body resp)))))

  (testing "returns created programs"
    (invoke programs/create {:body {:programName "prog-1"}})
    (invoke programs/create {:body {:programName "prog-2"}})
    (let [resp (invoke programs/search-all)]
      (is (= 2 (count (:body resp)))))))

(deftest create-test
  (testing "returns 201 with metadata"
    (let [resp (invoke programs/create {:body {:programName "new-prog"}})]
      (is (= 201 (:status resp)))
      (is (= "new-prog" (get-in resp [:body :programName])))
      (is (string? (get-in resp [:body :id])))
      (is (= "PROGRAM" (get-in resp [:body :objectType]))))))

(deftest get-by-id-test
  (let [created (:body (invoke programs/create {:body {:programName "findme"}}))
        id (:id created)]

    (testing "found"
      (let [resp (invoke programs/get-by-id {:path-params {:programID id}})]
        (is (= 200 (:status resp)))
        (is (= "findme" (get-in resp [:body :programName])))))

    (testing "not found"
      (let [resp (invoke programs/get-by-id {:path-params {:programID "no-such"}})]
        (is (= 404 (:status resp)))))))

(deftest update-by-id-test
  (let [created (:body (invoke programs/create {:body {:programName "original"}}))
        id (:id created)]

    (testing "successful update"
      (let [resp (invoke programs/update-by-id
                         {:path-params {:programID id}
                          :body {:programName "updated"}})]
        (is (= 200 (:status resp)))
        (is (= "updated" (get-in resp [:body :programName])))
        (is (= id (get-in resp [:body :id])))))

    (testing "update nonexistent"
      (let [resp (invoke programs/update-by-id
                         {:path-params {:programID "no-such"}
                          :body {:programName "x"}})]
        (is (= 404 (:status resp)))))))

(deftest delete-by-id-test
  (let [created (:body (invoke programs/create {:body {:programName "deleteme"}}))
        id (:id created)]

    (testing "successful delete"
      (let [resp (invoke programs/delete-by-id {:path-params {:programID id}})]
        (is (= 200 (:status resp)))))

    (testing "verify deleted"
      (let [resp (invoke programs/get-by-id {:path-params {:programID id}})]
        (is (= 404 (:status resp)))))

    (testing "delete nonexistent"
      (let [resp (invoke programs/delete-by-id {:path-params {:programID "no-such"}})]
        (is (= 404 (:status resp)))))))
