(ns openadr3.vtn.handler.common-test
  (:require [clojure.test :refer [deftest is testing]]
            [openadr3.vtn.handler.common :as common]))

(deftest new-id-test
  (testing "generates unique UUID strings"
    (let [id1 (common/new-id)
          id2 (common/new-id)]
      (is (string? id1))
      (is (string? id2))
      (is (not= id1 id2))
      (is (= 36 (count id1))))))

(deftest add-metadata-test
  (testing "adds id, timestamps, and objectType"
    (let [body {:programName "test-program"}
          result (common/add-metadata body "PROGRAM")]
      (is (= "test-program" (:programName result)))
      (is (string? (:id result)))
      (is (string? (:createdDateTime result)))
      (is (string? (:modificationDateTime result)))
      (is (= "PROGRAM" (:objectType result)))))

  (testing "timestamps are RFC 3339 with Z suffix"
    (let [result (common/add-metadata {} "EVENT")]
      (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z"
                      (:createdDateTime result))))))

(deftest touch-metadata-test
  (testing "preserves id and createdDateTime, updates modificationDateTime"
    (let [stored {:id "abc-123"
                  :createdDateTime "2026-01-01T00:00:00Z"
                  :modificationDateTime "2026-01-01T00:00:00Z"
                  :objectType "PROGRAM"
                  :programName "original"}
          updated {:programName "updated"}
          result (common/touch-metadata stored updated)]
      (is (= "abc-123" (:id result)))
      (is (= "2026-01-01T00:00:00Z" (:createdDateTime result)))
      (is (= "PROGRAM" (:objectType result)))
      (is (= "updated" (:programName result)))
      (is (not= "2026-01-01T00:00:00Z" (:modificationDateTime result))))))

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
