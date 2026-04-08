(ns openadr3.vtn.notifier-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [openadr3.vtn.notifier :as notifier]
            [openadr3.vtn.handler.common :as common]))

(deftest global-topic-test
  (testing "global topic for program create"
    (is (= "OpenADR/3.1.0/programs/create"
           (#'openadr3.vtn.notifier/global-topic "PROGRAM" "CREATE"))))
  (testing "global topic for event update"
    (is (= "OpenADR/3.1.0/events/update"
           (#'openadr3.vtn.notifier/global-topic "EVENT" "UPDATE"))))
  (testing "global topic for subscription delete"
    (is (= "OpenADR/3.1.0/subscriptions/delete"
           (#'openadr3.vtn.notifier/global-topic "SUBSCRIPTION" "DELETE")))))

(deftest scoped-topics-test
  (testing "event scoped to program"
    (let [event (common/add-metadata {:programID "p-123" :eventName "e1"} "EVENT")
          topics (#'openadr3.vtn.notifier/scoped-topics "EVENT" "CREATE" event)]
      (is (= ["OpenADR/3.1.0/programs/p-123/events/create"] topics))))

  (testing "program update scoped to its ID"
    (let [program (common/add-metadata {:programName "test"} "PROGRAM")
          topics (#'openadr3.vtn.notifier/scoped-topics "PROGRAM" "UPDATE" program)]
      (is (= 1 (count topics)))
      (is (str/includes? (first topics) (:id program)))))

  (testing "program create has no scoped topics"
    (let [program (common/add-metadata {:programName "test"} "PROGRAM")
          topics (#'openadr3.vtn.notifier/scoped-topics "PROGRAM" "CREATE" program)]
      (is (nil? topics))))

  (testing "subscription has no scoped topics"
    (let [sub (common/add-metadata {:clientName "c1"} "SUBSCRIPTION")]
      (is (nil? (#'openadr3.vtn.notifier/scoped-topics "SUBSCRIPTION" "CREATE" sub))))))

(deftest object-type->collection-test
  (is (= "programs" (#'openadr3.vtn.notifier/object-type->collection "PROGRAM")))
  (is (= "events" (#'openadr3.vtn.notifier/object-type->collection "EVENT")))
  (is (= "subscriptions" (#'openadr3.vtn.notifier/object-type->collection "SUBSCRIPTION")))
  (is (= "vens" (#'openadr3.vtn.notifier/object-type->collection "VEN")))
  (is (= "resources" (#'openadr3.vtn.notifier/object-type->collection "RESOURCE")))
  (is (= "reports" (#'openadr3.vtn.notifier/object-type->collection "REPORT"))))
