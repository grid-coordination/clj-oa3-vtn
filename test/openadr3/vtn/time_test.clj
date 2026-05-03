(ns openadr3.vtn.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [openadr3.vtn.time :as time])
  (:import [java.time Duration LocalDateTime ZoneId ZonedDateTime]))

(deftest parse-zdt-test
  (testing "Z form"
    (is (instance? ZonedDateTime (time/parse-zdt "2026-05-03T00:00:00Z"))))

  (testing "non-Z offset preserved"
    (let [zdt (time/parse-zdt "2026-05-03T00:00:00-07:00")]
      (is (instance? ZonedDateTime zdt))
      (is (= "-07:00" (str (.getZone zdt))))))

  (testing "VTN-RI space-separated form is normalised to UTC"
    (let [zdt (time/parse-zdt "2026-03-08 19:22:06")]
      (is (instance? ZonedDateTime zdt))
      (is (= "Z" (str (.getZone zdt)))))))

(deftest parse-zdt-maybe-test
  (testing "passes nil through"
    (is (nil? (time/parse-zdt-maybe nil))))

  (testing "passes already-parsed ZDT through unchanged"
    (let [zdt (time/parse-zdt "2026-05-03T00:00:00Z")]
      (is (= zdt (time/parse-zdt-maybe zdt))))))

(deftest zdt->utc-z-test
  (testing "Z input round-trips identically"
    (is (= "2026-05-03T00:00:00Z"
           (time/zdt->utc-z (time/parse-zdt "2026-05-03T00:00:00Z")))))

  (testing "non-Z offset is normalised to UTC Z (canonical form for OA3 wire / GSI)"
    (let [pt-zdt (.atZone (LocalDateTime/of 2026 5 3 0 0)
                          (ZoneId/of "America/Los_Angeles"))]
      ;; PDT in May is -07:00, so 00:00 PT == 07:00 UTC
      (is (= "2026-05-03T07:00:00Z" (time/zdt->utc-z pt-zdt)))))

  (testing "non-Z wire offset normalises to canonical UTC Z"
    (is (= "2026-05-03T07:00:00Z"
           (time/zdt->utc-z (time/parse-zdt "2026-05-03T00:00:00-07:00"))))))

(deftest plus-days-dst-test
  (testing "PT spring-forward 2026-03-08: plusDays preserves PT midnight"
    (let [pt-mid (.atZone (LocalDateTime/of 2026 3 8 0 0)
                          (ZoneId/of "America/Los_Angeles"))
          next   (time/plus-days pt-mid 1)]
      ;; The next midnight in PT is the next calendar day's midnight,
      ;; even though the UTC offset shifts from -08 to -07. Real elapsed
      ;; time is 23 hours, not 24 — that's the DST-correct answer.
      (is (= 9 (.getDayOfMonth next)))
      (is (= 0 (.getHour next))))))

(deftest json-zdt-extension-test
  (testing "ZonedDateTime JSON-encodes as canonical UTC Z string"
    (is (= "\"2026-05-03T07:00:00Z\""
           (json/write-str (time/parse-zdt "2026-05-03T00:00:00-07:00")))))

  (testing "Duration JSON-encodes as ISO 8601 string"
    (is (= "\"PT1H\"" (json/write-str (Duration/parse "PT1H")))))

  (testing "ZDT/Duration nested in a map JSON-encode"
    (let [m {:start    (time/parse-zdt "2026-05-03T00:00:00Z")
             :duration (Duration/parse "PT15M")}]
      (is (= "{\"start\":\"2026-05-03T00:00:00Z\",\"duration\":\"PT15M\"}"
             (json/write-str m))))))
