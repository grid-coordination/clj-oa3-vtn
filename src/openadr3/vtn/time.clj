(ns openadr3.vtn.time
  "RFC 3339 datetime formatting helpers."
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]))

(defn now-rfc3339
  "Current UTC time as RFC 3339 string with Z suffix."
  []
  (.format DateTimeFormatter/ISO_INSTANT (Instant/now)))
