(ns openadr3.vtn.handler.notifiers-test
  (:require [clojure.test :refer [deftest is testing]]
            [openadr3.vtn.handler.notifiers :as notifiers]))

(def test-config {:mqtt-broker-url "tcp://localhost:1883"})

(deftest list-all-test
  (let [handler (notifiers/list-all test-config)
        resp (handler {})]

    (testing "returns 200"
      (is (= 200 (:status resp))))

    (testing "WEBHOOK is true"
      (is (true? (get-in resp [:body :WEBHOOK]))))

    (testing "MQTT binding present with broker URL"
      (let [mqtt (get-in resp [:body :MQTT])]
        (is (= ["tcp://localhost:1883"] (:URIS mqtt)))
        (is (= "JSON" (:serialization mqtt)))
        (is (= "ANONYMOUS" (get-in mqtt [:authentication :method])))))))
