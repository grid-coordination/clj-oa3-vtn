(ns openadr3.vtn.handler.common-test
  (:require [clojure.test :refer [deftest is testing]]
            [openadr3.vtn.handler.common :as common]
            [openadr3.vtn.time :as vtn-time])
  (:import [java.time Duration ZonedDateTime]))

(deftest new-id-test
  (testing "generates unique UUID strings"
    (let [id1 (common/new-id)
          id2 (common/new-id)]
      (is (string? id1))
      (is (string? id2))
      (is (not= id1 id2))
      (is (= 36 (count id1))))))

(deftest add-metadata-test
  (testing "adds id, timestamps (ZonedDateTime), and objectType"
    (let [body {:programName "test-program"}
          result (common/add-metadata body "PROGRAM")]
      (is (= "test-program" (:programName result)))
      (is (string? (:id result)))
      (is (instance? ZonedDateTime (:createdDateTime result)))
      (is (instance? ZonedDateTime (:modificationDateTime result)))
      (is (= "PROGRAM" (:objectType result)))))

  (testing "createdDateTime serialises to canonical UTC Z"
    (let [result (common/add-metadata {} "EVENT")
          wire   (vtn-time/zdt->utc-z (:createdDateTime result))]
      (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z" wire)))))

(deftest touch-metadata-test
  (testing "preserves id and createdDateTime, updates modificationDateTime"
    (let [orig    (vtn-time/parse-zdt "2026-01-01T00:00:00Z")
          stored  {:id "abc-123"
                   :createdDateTime orig
                   :modificationDateTime orig
                   :objectType "PROGRAM"
                   :programName "original"}
          updated {:programName "updated"}
          result  (common/touch-metadata stored updated)]
      (is (= "abc-123" (:id result)))
      (is (= orig (:createdDateTime result)))
      (is (= "PROGRAM" (:objectType result)))
      (is (= "updated" (:programName result)))
      (is (not= orig (:modificationDateTime result))))))

(deftest coerce-event-body-test
  (testing "parses string intervalPeriod fields to ZDT/Duration"
    (let [body {:programID "p1"
                :intervalPeriod {:start "2026-05-03T00:00:00-07:00"
                                 :duration "PT1H"}
                :intervals [{:id 0
                             :intervalPeriod {:start "2026-05-03T01:00:00-07:00"}}]}
          out  (common/coerce-event-body body)]
      (is (instance? ZonedDateTime (get-in out [:intervalPeriod :start])))
      (is (instance? Duration (get-in out [:intervalPeriod :duration])))
      (is (instance? ZonedDateTime (get-in out [:intervals 0 :intervalPeriod :start])))))

  (testing "is idempotent on already-parsed values"
    (let [zdt (vtn-time/parse-zdt "2026-05-03T00:00:00Z")
          body {:intervalPeriod {:start zdt :duration (Duration/parse "PT1H")}}
          out  (common/coerce-event-body body)]
      (is (= zdt (get-in out [:intervalPeriod :start]))))))

(deftest event-search-window-test
  (testing "no params → overlap window of [now, now+2d]"
    (let [w (common/event-search-window {})]
      (is (instance? ZonedDateTime (:active-from w)))
      (is (instance? ZonedDateTime (:active-until w)))
      (is (nil? (:date-start w)))))

  (testing "explicit dateStart/dateEnd → date-start/date-end ZDTs"
    (let [w (common/event-search-window {:dateStart "2026-05-03T00:00:00Z"
                                         :dateEnd   "2026-05-04T00:00:00Z"})]
      (is (instance? ZonedDateTime (:date-start w)))
      (is (instance? ZonedDateTime (:date-end w)))
      (is (nil? (:active-from w))))))

(deftest paginate-test
  (let [items (mapv #(hash-map :n %) (range 20))]

    (testing "defaults to skip=0 limit=50"
      (is (= 20 (count (common/paginate items {})))))

    (testing "respects skip"
      (is (= 15 (count (common/paginate items {:skip 5})))))

    (testing "respects limit"
      (is (= 3 (count (common/paginate items {:limit 3})))))

    (testing "respects skip + limit"
      (let [result (common/paginate items {:skip 5 :limit 3})]
        (is (= 3 (count result)))
        (is (= 5 (:n (first result))))))))

(deftest parse-pagination-test
  (testing "parses string params to integers"
    (is (= {:skip 10 :limit 20}
           (common/parse-pagination {:skip "10" :limit "20"}))))

  (testing "caps limit at 50"
    (is (= {:limit 50}
           (common/parse-pagination {:limit "100"}))))

  (testing "handles missing params"
    (is (= {} (common/parse-pagination {})))))

(deftest error-responses-test
  (testing "not-found"
    (let [resp (common/not-found "Program" "abc")]
      (is (= 404 (:status resp)))
      (is (= "Not Found" (get-in resp [:body :title])))))

  (testing "conflict"
    (let [resp (common/conflict "duplicate")]
      (is (= 409 (:status resp)))
      (is (= "duplicate" (get-in resp [:body :detail])))))

  (testing "not-implemented"
    (is (= 501 (:status (common/not-implemented))))))
