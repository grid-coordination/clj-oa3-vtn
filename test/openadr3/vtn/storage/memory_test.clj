(ns openadr3.vtn.storage.memory-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as store]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.handler.common :as common]))

(def ^:dynamic *storage* nil)

(defn storage-fixture [f]
  (binding [*storage* (component/start (mem/new-atom-storage))]
    (try (f)
         (finally (component/stop *storage*)))))

(use-fixtures :each storage-fixture)

(defn make-program [name]
  (common/add-metadata {:programName name} "PROGRAM"))

(defn make-event [program-id]
  (common/add-metadata {:programID program-id :eventName "test-event"} "EVENT"))

(defn make-subscription [program-id]
  (common/add-metadata {:programID program-id
                        :clientName "test-client"
                        :objectOperations [{:objects ["PROGRAM"]
                                            :operations ["CREATE"]}]}
                       "SUBSCRIPTION"))

;; --- Program tests ---

(deftest program-crud-test
  (testing "create and get"
    (let [p (store/create-program *storage* (make-program "PG&E-TOU"))
          fetched (store/get-program *storage* (:id p))]
      (is (= "PG&E-TOU" (:programName fetched)))
      (is (= (:id p) (:id fetched)))))

  (testing "list programs"
    (store/create-program *storage* (make-program "SCE-TOU"))
    (let [all (store/list-programs *storage* {})]
      (is (= 2 (count all)))))

  (testing "update program"
    (let [p (first (store/list-programs *storage* {}))
          updated (store/update-program *storage* (:id p)
                                        (common/touch-metadata p (assoc p :programName "updated")))]
      (is (= "updated" (:programName updated)))
      (is (= (:id p) (:id updated)))
      (is (= (:createdDateTime p) (:createdDateTime updated)))))

  (testing "update nonexistent returns nil"
    (is (nil? (store/update-program *storage* "no-such-id" {}))))

  (testing "delete program"
    (let [all-before (store/list-programs *storage* {})
          p (first all-before)
          deleted (store/delete-program *storage* (:id p))]
      (is (some? deleted))
      (is (nil? (store/get-program *storage* (:id p))))
      (is (= (dec (count all-before))
             (count (store/list-programs *storage* {}))))))

  (testing "delete nonexistent returns nil"
    (is (nil? (store/delete-program *storage* "no-such-id")))))

;; --- Event tests ---

(deftest event-crud-test
  (let [p (store/create-program *storage* (make-program "test-prog"))]

    (testing "create and get event"
      (let [e (store/create-event *storage* (make-event (:id p)))
            fetched (store/get-event *storage* (:id e))]
        (is (= (:id p) (:programID fetched)))))

    (testing "list events filters by programID"
      (store/create-event *storage* (make-event (:id p)))
      (store/create-event *storage* (make-event "other-program"))
      (let [filtered (store/list-events *storage* {:programID (:id p)})]
        (is (= 2 (count filtered)))
        (is (every? #(= (:id p) (:programID %)) filtered))))

    (testing "update event"
      (let [e (first (store/list-events *storage* {:programID (:id p)}))
            updated (store/update-event *storage* (:id e)
                                        (common/touch-metadata e (assoc e :priority 1)))]
        (is (= 1 (:priority updated)))))

    (testing "delete event"
      (let [e (first (store/list-events *storage* {:programID (:id p)}))]
        (is (some? (store/delete-event *storage* (:id e))))
        (is (nil? (store/get-event *storage* (:id e))))))))

;; --- Subscription tests ---

(deftest subscription-crud-test
  (let [p (store/create-program *storage* (make-program "sub-prog"))]

    (testing "create and get subscription"
      (let [s (store/create-subscription *storage* (make-subscription (:id p)))
            fetched (store/get-subscription *storage* (:id s))]
        (is (= "test-client" (:clientName fetched)))))

    (testing "list subscriptions filters by programID"
      (store/create-subscription *storage* (make-subscription "other-prog"))
      (let [filtered (store/list-subscriptions *storage* {:programID (:id p)})]
        (is (= 1 (count filtered)))))

    (testing "list subscriptions filters by clientName"
      (let [all (store/list-subscriptions *storage* {:clientName "test-client"})]
        (is (= 2 (count all)))))

    (testing "update subscription"
      (let [s (first (store/list-subscriptions *storage* {:programID (:id p)}))
            updated (store/update-subscription *storage* (:id s)
                                               (common/touch-metadata s (assoc s :clientName "new-client")))]
        (is (= "new-client" (:clientName updated)))))

    (testing "delete subscription"
      (let [s (first (store/list-subscriptions *storage* {:programID (:id p)}))]
        (is (some? (store/delete-subscription *storage* (:id s))))
        (is (nil? (store/get-subscription *storage* (:id s))))))))

;; --- Pagination tests ---

(deftest pagination-test
  (testing "skip and limit on programs"
    (dotimes [i 10]
      (store/create-program *storage* (make-program (str "prog-" i))))
    (let [page1 (store/list-programs *storage* {:skip 0 :limit 3})
          page2 (store/list-programs *storage* {:skip 3 :limit 3})]
      (is (= 3 (count page1)))
      (is (= 3 (count page2)))
      (is (empty? (clojure.set/intersection
                   (set (map :id page1))
                   (set (map :id page2))))))))

;; --- Target filtering tests ---

(deftest target-filtering-test
  (testing "programs with matching targets"
    (store/create-program *storage*
                          (common/add-metadata {:programName "targeted"
                                                :targets [{:type "GRID_ZONE" :values ["zone-1"]}]}
                                               "PROGRAM"))
    (store/create-program *storage*
                          (common/add-metadata {:programName "other"
                                                :targets [{:type "GRID_ZONE" :values ["zone-2"]}]}
                                               "PROGRAM"))
    (store/create-program *storage*
                          (common/add-metadata {:programName "no-targets"} "PROGRAM"))

    (testing "filter by matching target"
      (let [results (store/list-programs *storage*
                                         {:targets [{:type "GRID_ZONE" :values ["zone-1"]}]})]
        (is (= 1 (count results)))
        (is (= "targeted" (:programName (first results))))))

    (testing "no filter returns all"
      (is (= 3 (count (store/list-programs *storage* {})))))))
