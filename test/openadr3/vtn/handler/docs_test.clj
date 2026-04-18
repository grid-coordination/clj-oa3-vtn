(ns openadr3.vtn.handler.docs-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [openadr3.vtn.handler.docs :as docs]))

(deftest filter-spec-test
  (let [spec {"paths" {"/programs"    {"get" {"summary" "list"} "post" {"summary" "create"}}
                       "/events"      {"get" {"summary" "list"} "delete" {"summary" "del"}}
                       "/reports"     {"get" {"summary" "list"}}
                       "/auth/server" {"get" {"summary" "info"}}}}
        handler-map {[:get "/programs"]    identity
                     [:get "/events"]      identity
                     [:get "/auth/server"] identity}
        filtered (#'docs/filter-spec spec handler-map)]

    (testing "keeps matched paths and methods"
      (is (= {"get" {"summary" "list"}}
             (get-in filtered ["paths" "/programs"])))
      (is (= {"get" {"summary" "list"}}
             (get-in filtered ["paths" "/events"])))
      (is (= {"get" {"summary" "info"}}
             (get-in filtered ["paths" "/auth/server"]))))

    (testing "removes unmatched methods"
      (is (nil? (get-in filtered ["paths" "/programs" "post"])))
      (is (nil? (get-in filtered ["paths" "/events" "delete"]))))

    (testing "removes paths with no remaining methods"
      (is (nil? (get-in filtered ["paths" "/reports"]))))))

(deftest brand-spec-test
  (let [spec {"info" {"title" "Original" "version" "1.0"}}
        branded (#'docs/brand-spec spec {:context-path "/openadr3/3.1.0"
                                         :docs-title "My VTN"})]
    (testing "sets custom title"
      (is (= "My VTN" (get-in branded ["info" "title"]))))
    (testing "sets server URL from context-path"
      (is (= [{"url" "/openadr3/3.1.0"}] (get branded "servers"))))))

(deftest docs-page-handler-test
  (let [handler (docs/docs-page)
        resp (handler {:uri "/docs" :request-method :get})]
    (testing "returns 200 with HTML"
      (is (= 200 (:status resp)))
      (is (re-find #"text/html" (get-in resp [:headers "content-type"])))
      (is (re-find #"scalar" (:body resp))))))

(deftest openapi-json-handler-test
  (let [handler-map {[:get "/programs"] identity
                     [:get "/events"]   identity}
        handler (docs/openapi-json handler-map {:context-path "/api"})
        resp (handler {:uri "/openapi.json" :request-method :get})
        body (json/read-str (:body resp))]
    (testing "returns 200 with JSON"
      (is (= 200 (:status resp)))
      (is (re-find #"application/json" (get-in resp [:headers "content-type"]))))
    (testing "spec is filtered to only matched paths"
      (is (contains? (get body "paths") "/programs"))
      (is (contains? (get body "paths") "/events"))
      (is (not (contains? (get body "paths") "/reports"))))
    (testing "spec has server URL"
      (is (= "/api" (get-in body ["servers" 0 "url"]))))))
