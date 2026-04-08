(ns openadr3.vtn.handler.topics-test
  (:require [clojure.test :refer [deftest is testing]]
            [openadr3.vtn.handler.topics :as topics]))

(deftest collection-level-topics-test
  (testing "programs topics include CREATE"
    (let [resp ((topics/programs-topics) {})
          t (get-in resp [:body :topics])]
      (is (= 200 (:status resp)))
      (is (= "OpenADR/3.1.0/programs/create" (:CREATE t)))
      (is (= "OpenADR/3.1.0/programs/update" (:UPDATE t)))
      (is (= "OpenADR/3.1.0/programs/delete" (:DELETE t)))
      (is (= "OpenADR/3.1.0/programs/+" (:ALL t)))))

  (testing "events topics"
    (let [t (get-in ((topics/events-topics) {}) [:body :topics])]
      (is (= "OpenADR/3.1.0/events/create" (:CREATE t)))))

  (testing "reports topics"
    (let [t (get-in ((topics/reports-topics) {}) [:body :topics])]
      (is (= "OpenADR/3.1.0/reports/create" (:CREATE t)))))

  (testing "subscriptions topics"
    (let [t (get-in ((topics/subscriptions-topics) {}) [:body :topics])]
      (is (= "OpenADR/3.1.0/subscriptions/create" (:CREATE t)))))

  (testing "vens topics"
    (let [t (get-in ((topics/vens-topics) {}) [:body :topics])]
      (is (= "OpenADR/3.1.0/vens/create" (:CREATE t)))))

  (testing "resources topics"
    (let [t (get-in ((topics/resources-topics) {}) [:body :topics])]
      (is (= "OpenADR/3.1.0/resources/create" (:CREATE t))))))

(deftest object-level-topics-test
  (testing "program topics omit CREATE"
    (let [resp ((topics/program-topics) {:path-params {:programID "p-123"}})
          t (get-in resp [:body :topics])]
      (is (nil? (:CREATE t)))
      (is (= "OpenADR/3.1.0/programs/p-123/update" (:UPDATE t)))
      (is (= "OpenADR/3.1.0/programs/p-123/delete" (:DELETE t)))))

  (testing "program events topics include CREATE"
    (let [t (get-in ((topics/program-events-topics) {:path-params {:programID "p-123"}})
                    [:body :topics])]
      (is (= "OpenADR/3.1.0/programs/p-123/events/create" (:CREATE t)))))

  (testing "VEN-scoped topics"
    (let [t (get-in ((topics/ven-topics) {:path-params {:venID "v-456"}})
                    [:body :topics])]
      (is (nil? (:CREATE t)))
      (is (= "OpenADR/3.1.0/vens/v-456/update" (:UPDATE t))))

    (let [t (get-in ((topics/ven-events-topics) {:path-params {:venID "v-456"}})
                    [:body :topics])]
      (is (= "OpenADR/3.1.0/vens/v-456/events/create" (:CREATE t))))

    (let [t (get-in ((topics/ven-programs-topics) {:path-params {:venID "v-456"}})
                    [:body :topics])]
      (is (= "OpenADR/3.1.0/vens/v-456/programs/create" (:CREATE t))))

    (let [t (get-in ((topics/ven-resources-topics) {:path-params {:venID "v-456"}})
                    [:body :topics])]
      (is (= "OpenADR/3.1.0/vens/v-456/resources/create" (:CREATE t))))))
