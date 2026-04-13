(ns openadr3.vtn.config-test
  "Tests for config loading with external file support."
  (:require [clojure.test :refer [deftest is testing]]
            [openadr3.vtn.config :as config]))

(deftest load-config-defaults-test
  (testing "load-config returns defaults when no external path set"
    (let [cfg (config/load-config)]
      (is (= 8080 (:ven-port cfg)))
      (is (= 8081 (:bl-port cfg)))
      (is (string? (:context-path cfg))))))

(deftest load-config-system-property-test
  (testing "system property overrides classpath config"
    (let [tmp (java.io.File/createTempFile "config-test" ".edn")]
      (spit tmp (pr-str {:ven-port 9999 :custom-key "external"}))
      (try
        (System/setProperty "openadr3.config" (.getAbsolutePath tmp))
        (let [cfg (config/load-config)]
          (is (= 9999 (:ven-port cfg)))
          (is (= "external" (:custom-key cfg)))
          (is (= 8081 (:bl-port cfg)) "defaults still fill gaps"))
        (finally
          (System/clearProperty "openadr3.config")
          (.delete tmp))))))

(deftest load-config-missing-file-fallback-test
  (testing "nonexistent external path falls back to classpath"
    (try
      (System/setProperty "openadr3.config" "/nonexistent/config.edn")
      (let [cfg (config/load-config)]
        (is (= 8080 (:ven-port cfg))))
      (finally
        (System/clearProperty "openadr3.config")))))
