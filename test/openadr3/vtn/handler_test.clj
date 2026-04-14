(ns openadr3.vtn.handler-test
  "Tests for VEN handler map route enablement."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [openadr3.vtn.handler :as handler]))

;; Stubs — we only care about which route keys are present, not handlers
(def stub-storage nil)
(def _unused nil)

(defn- route-keys
  "Get the set of route keys from a handler map."
  [handler-map]
  (set (keys handler-map)))

(deftest default-ven-routes-test
  (let [config {}
        routes (route-keys (handler/ven-handler-map stub-storage config))]

    (testing "programs read-only by default"
      (is (routes [:get "/programs"]))
      (is (routes [:get "/programs/{programID}"]))
      (is (not (routes [:post "/programs"])))
      (is (not (routes [:put "/programs/{programID}"])))
      (is (not (routes [:delete "/programs/{programID}"]))))

    (testing "events read-only by default"
      (is (routes [:get "/events"]))
      (is (routes [:get "/events/{eventID}"]))
      (is (not (routes [:post "/events"])))
      (is (not (routes [:put "/events/{eventID}"])))
      (is (not (routes [:delete "/events/{eventID}"]))))

    (testing "subscriptions disabled by default"
      (is (not (routes [:get "/subscriptions"])))
      (is (not (routes [:post "/subscriptions"])))
      (is (not (routes [:get "/subscriptions/{subscriptionID}"])))
      (is (not (routes [:put "/subscriptions/{subscriptionID}"])))
      (is (not (routes [:delete "/subscriptions/{subscriptionID}"]))))

    (testing "subscription topic discovery suppressed"
      (is (not (routes [:get "/notifiers/mqtt/topics/subscriptions"]))))

    (testing "programs and events topic discovery present"
      (is (routes [:get "/notifiers/mqtt/topics/programs"]))
      (is (routes [:get "/notifiers/mqtt/topics/events"])))

    (testing "vens/resources/reports topic discovery suppressed"
      (is (not (routes [:get "/notifiers/mqtt/topics/vens"])))
      (is (not (routes [:get "/notifiers/mqtt/topics/resources"])))
      (is (not (routes [:get "/notifiers/mqtt/topics/reports"]))))

    (testing "auth and notifiers always present"
      (is (routes [:get "/notifiers"]))
      (is (routes [:get "/auth/server"]))
      (is (routes [:post "/auth/token"])))))

(deftest full-crud-ven-routes-test
  (let [config {:ven-routes {:subscriptions :full}}
        routes (route-keys (handler/ven-handler-map stub-storage config))]

    (testing "subscriptions full CRUD when enabled"
      (is (routes [:get "/subscriptions"]))
      (is (routes [:post "/subscriptions"]))
      (is (routes [:get "/subscriptions/{subscriptionID}"]))
      (is (routes [:put "/subscriptions/{subscriptionID}"]))
      (is (routes [:delete "/subscriptions/{subscriptionID}"])))

    (testing "subscription topic discovery present"
      (is (routes [:get "/notifiers/mqtt/topics/subscriptions"])))))

(deftest read-only-subscriptions-test
  (let [config {:ven-routes {:subscriptions :read-only}}
        routes (route-keys (handler/ven-handler-map stub-storage config))]

    (testing "subscriptions read-only: GET only"
      (is (routes [:get "/subscriptions"]))
      (is (routes [:get "/subscriptions/{subscriptionID}"]))
      (is (not (routes [:post "/subscriptions"])))
      (is (not (routes [:put "/subscriptions/{subscriptionID}"])))
      (is (not (routes [:delete "/subscriptions/{subscriptionID}"]))))))

(deftest disabled-programs-test
  (let [config {:ven-routes {:programs false}}
        routes (route-keys (handler/ven-handler-map stub-storage config))]

    (testing "programs completely disabled"
      (is (not (routes [:get "/programs"])))
      (is (not (routes [:get "/programs/{programID}"]))))

    (testing "program topic discovery suppressed"
      (is (not (routes [:get "/notifiers/mqtt/topics/programs"])))
      (is (not (routes [:get "/notifiers/mqtt/topics/programs/{programID}"]))))))

(deftest not-found-json-response-test
  (testing "routing handler returns RFC 9457 problem+json for unmatched routes"
    (let [handler-map (handler/bl-handler-map stub-storage
                                              {:mqtt-broker-url "mqtt://localhost:1883"
                                               :bl-notifiers {}})
          routing-handler (handler/make-routing-handler handler-map)
          resp (routing-handler {:request-method :get :uri "/no-such-route"})]
      (is (= 404 (:status resp)))
      (is (= "application/problem+json" (get-in resp [:headers "content-type"])))
      (let [body (json/read-str (:body resp))]
        (is (= "about:blank" (get body "type")))
        (is (= "Not Found" (get body "title")))
        (is (= 404 (get body "status")))))))
