(ns openadr3.vtn.time
  "Time helpers for the VTN.

  The VTN's internal canon is `java.time.ZonedDateTime` for any datetime
  value (createdDateTime, modificationDateTime, intervalPeriod.start) and
  `java.time.Duration` for any duration value (intervalPeriod.duration).
  All zone-sensitive arithmetic happens on `ZonedDateTime` so DST is
  handled correctly by the IANA rules.

  At the wire boundary (HTTP response, MQTT publish, DDB :data attribute,
  and the DDB :eventStart GSI key) datetimes are serialised to canonical
  UTC `Z` form via `zdt->utc-z`. The OA3 spec strongly conventionalises Z
  form, and the GSI relies on lex-orderable sort keys, so canonicalising
  the wire keeps both consistent.

  Inbound parsing accepts arbitrary RFC 3339 offsets (`Z`, `+00:00`,
  `-07:00`, …) and the VTN-RI's non-standard space-separated form."
  (:require [clojure.data.json :as json])
  (:import [java.time Duration OffsetDateTime ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn now-zdt
  "Current instant as a UTC ZonedDateTime."
  ^ZonedDateTime []
  (ZonedDateTime/now ZoneOffset/UTC))

(defn plus-days
  "Add n calendar days to a ZonedDateTime, respecting the zone's DST rules."
  ^ZonedDateTime [^ZonedDateTime zdt n]
  (.plusDays zdt n))

;; ---------------------------------------------------------------------------
;; Parsing (wire string → ZDT)
;; ---------------------------------------------------------------------------

(defn parse-zdt
  "Parse an RFC 3339 datetime string to a ZonedDateTime.

  Accepts arbitrary offsets per RFC 3339 (`Z`, `+00:00`, `-07:00`, …).
  The returned ZonedDateTime is zoned to the wire offset (no IANA name
  is recoverable from the wire).

  Also accepts the VTN-RI's non-standard space-separated form
  (`2026-03-08 19:22:06`, no offset) by inserting `T` and assuming UTC."
  ^ZonedDateTime [^String s]
  (let [normalized (if (.contains s "T")
                     s
                     (str (.replace s " " "T") "Z"))]
    (.toZonedDateTime (OffsetDateTime/parse normalized))))

(defn parse-zdt-maybe
  "Parse to ZonedDateTime if `s` is a non-blank string; pass through nil
  and already-parsed ZonedDateTime values."
  [s]
  (cond
    (nil? s) nil
    (instance? ZonedDateTime s) s
    (and (string? s) (not (.isBlank ^String s))) (parse-zdt s)))

(defn parse-duration
  "Parse an ISO 8601 duration string to a java.time.Duration."
  ^Duration [^String s]
  (Duration/parse s))

(defn parse-duration-maybe
  "Parse to Duration if `s` is a non-blank string; pass through nil and
  already-parsed Duration values."
  [s]
  (cond
    (nil? s) nil
    (instance? Duration s) s
    (and (string? s) (not (.isBlank ^String s))) (parse-duration s)))

;; ---------------------------------------------------------------------------
;; Serialisation (ZDT → wire string)
;; ---------------------------------------------------------------------------

(defn zdt->utc-z
  "Format a ZonedDateTime as canonical UTC Z ISO 8601 (`2026-05-03T07:00:00Z`).
   Normalises to UTC regardless of the zone the value carries — this is the
   wire form for OA3 and the canonical form for the DDB :eventStart GSI."
  ^String [^ZonedDateTime zdt]
  (-> zdt
      (.withZoneSameInstant ZoneOffset/UTC)
      (.format DateTimeFormatter/ISO_INSTANT)))

(defn duration->iso
  "Format a Duration as its ISO 8601 string (`PT1H`, `PT15M`, …)."
  ^String [^Duration d]
  (.toString d))

(defn now-utc-z
  "Current instant as a canonical UTC Z ISO 8601 string. Convenience wrapper."
  ^String []
  (zdt->utc-z (now-zdt)))

;; ---------------------------------------------------------------------------
;; JSON serialisation
;;
;; clojure.data.json/write-str dispatches on the JSONWriter protocol; we
;; extend it once at namespace load so any code path that JSON-encodes a
;; map carrying a ZonedDateTime or Duration produces canonical wire form.
;; This covers HTTP responses (wrap-json-response), MQTT payloads, the
;; DDB :data blob, and any test-side json/write-str.
;; ---------------------------------------------------------------------------

(extend-protocol json/JSONWriter
  ZonedDateTime
  (-write [zdt out options]
    (json/-write (zdt->utc-z zdt) out options))

  Duration
  (-write [d out options]
    (json/-write (duration->iso d) out options)))
