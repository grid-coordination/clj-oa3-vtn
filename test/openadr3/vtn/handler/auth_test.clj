(ns openadr3.vtn.handler.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [openadr3.vtn.handler.auth :as auth]))

(def test-config {:bl-port 8081 :context-path "/openadr3/3.1.0"})

(deftest server-info-test
  (let [handler (auth/server-info test-config)
        resp (handler {})]

    (testing "returns 200"
      (is (= 200 (:status resp))))

    (testing "returns tokenURL"
      (is (= "http://localhost:8081/openadr3/3.1.0/auth/token"
             (get-in resp [:body :tokenURL]))))))

(deftest fetch-token-test
  (let [handler (auth/fetch-token)
        resp (handler {})]

    (testing "returns 501"
      (is (= 501 (:status resp))))))
