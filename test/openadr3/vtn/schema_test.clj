(ns openadr3.vtn.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [openadr3.vtn.schema :as schema]
            [openadr3.vtn.handler.common :as common]))

(deftest notification-payload-test
  (testing "builds valid notification map"
    (let [program (common/add-metadata {:programName "PG&E-TOU"} "PROGRAM")
          payload (schema/notification-payload "PROGRAM" "CREATE" program)]
      (is (= "PROGRAM" (:objectType payload)))
      (is (= "CREATE" (:operation payload)))
      (is (= "PG&E-TOU" (get-in payload [:object :programName])))
      (is (nil? (schema/validate-notification-payload payload)))))

  (testing "invalid payload fails validation"
    (is (some? (schema/validate-notification-payload
                {:objectType "INVALID" :operation "CREATE" :object {}})))))

(deftest coerce-stored-program-test
  (testing "coerces a stored program to namespaced entity"
    (let [raw (common/add-metadata {:programName "test"} "PROGRAM")
          coerced (schema/coerce-stored raw)]
      (is (= "test" (:openadr.program/name coerced)))
      (is (= :openadr.object-type/program (:openadr/object-type coerced)))
      (is (string? (:openadr/id coerced)))
      (is (inst? (:openadr/created coerced))))))

(deftest coerce-stored-event-test
  (testing "coerces a stored event with intervals"
    (let [raw (common/add-metadata
               {:programID "p1"
                :eventName "price-signal"
                :priority 1
                :intervals [{:id 0
                             :payloads [{:type "PRICE" :values [0.25]}]}]}
               "EVENT")
          coerced (schema/coerce-stored raw)]
      (is (= :openadr.object-type/event (:openadr/object-type coerced)))
      (is (= "p1" (:openadr.event/program-id coerced)))
      (is (= 1 (:openadr.event/priority coerced)))
      (is (= 1 (count (:openadr.event/intervals coerced))))
      (let [interval (first (:openadr.event/intervals coerced))
            payload (first (:openadr.interval/payloads interval))]
        (is (= :openadr.payload-type/price (:openadr.payload/type payload)))
        (is (= [0.25M] (:openadr.payload/values payload)))))))

(deftest coerce-stored-subscription-test
  (testing "coerces a stored subscription"
    (let [raw (common/add-metadata
               {:programID "p1"
                :clientName "test-ven"
                :clientID "client-123"
                :objectOperations [{:objects ["EVENT"]
                                    :operations ["CREATE" "UPDATE"]
                                    :callbackUrl "http://example.com/callback"}]}
               "SUBSCRIPTION")
          coerced (schema/coerce-stored raw)]
      (is (= :openadr.object-type/subscription (:openadr/object-type coerced)))
      (is (= "test-ven" (:openadr.subscription/client-name coerced)))
      (is (= "p1" (:openadr.subscription/program-id coerced)))
      (let [op (first (:openadr.subscription/object-operations coerced))]
        (is (= [:openadr.object-type/event] (:openadr.object-operation/objects op)))
        (is (= [:openadr.operation/create :openadr.operation/update]
               (:openadr.object-operation/operations op)))))))

(deftest coerce-batch-test
  (testing "coerce-stored-programs handles multiple"
    (let [progs (mapv #(common/add-metadata {:programName (str "p" %)} "PROGRAM")
                      (range 3))
          coerced (schema/coerce-stored-programs progs)]
      (is (= 3 (count coerced)))
      (is (every? #(= :openadr.object-type/program (:openadr/object-type %)) coerced)))))
