(ns openadr3.vtn.time
  "RFC 3339 datetime formatting helpers."
  (:import [java.time Instant LocalDate ZoneOffset]
           [java.time.format DateTimeFormatter]))

(defn now-rfc3339
  "Current UTC time as RFC 3339 string with Z suffix."
  []
  (.format DateTimeFormatter/ISO_INSTANT (Instant/now)))

(defn today-start
  "Start of today (midnight UTC) as RFC 3339 string."
  []
  (let [today (LocalDate/now ZoneOffset/UTC)]
    (.format DateTimeFormatter/ISO_INSTANT
             (.toInstant (.atStartOfDay today ZoneOffset/UTC)))))

(defn tomorrow-end
  "End of tomorrow (midnight UTC of the day after tomorrow) as RFC 3339 string."
  []
  (let [day-after-tomorrow (.plusDays (LocalDate/now ZoneOffset/UTC) 2)]
    (.format DateTimeFormatter/ISO_INSTANT
             (.toInstant (.atStartOfDay day-after-tomorrow ZoneOffset/UTC)))))
