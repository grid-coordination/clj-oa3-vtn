(ns openadr3.vtn.handler.notifiers-test
  (:require [clojure.test :refer [deftest is testing]]
            [openadr3.vtn.handler.notifiers :as notifiers]))

(def test-config {:mqtt-broker-url "mqtt://localhost:1883"})

(deftest bl-notifiers-test
  (let [handler (notifiers/list-all test-config {:MQTT {} :WEBHOOK true})
        resp (handler {})]

    (testing "returns 200"
      (is (= 200 (:status resp))))

    (testing "WEBHOOK is true"
      (is (true? (get-in resp [:body :WEBHOOK]))))

    (testing "MQTT binding present with broker URL"
      (let [mqtt (get-in resp [:body :MQTT])]
        (is (= ["mqtt://localhost:1883"] (:URIS mqtt)))
        (is (= "JSON" (:serialization mqtt)))
        (is (= "ANONYMOUS" (get-in mqtt [:authentication :method])))))))

(deftest ven-notifiers-mqtt-only-test
  (let [handler (notifiers/list-all test-config {:MQTT {:authentication {:method "ANONYMOUS"}}})
        resp (handler {})]

    (testing "returns 200"
      (is (= 200 (:status resp))))

    (testing "WEBHOOK is false"
      (is (false? (get-in resp [:body :WEBHOOK]))))

    (testing "MQTT present"
      (is (some? (get-in resp [:body :MQTT]))))))

(deftest default-notifiers-test
  (let [handler (notifiers/list-all test-config nil)
        resp (handler {})]

    (testing "defaults include both WEBHOOK and MQTT"
      (is (true? (get-in resp [:body :WEBHOOK])))
      (is (some? (get-in resp [:body :MQTT]))))))
