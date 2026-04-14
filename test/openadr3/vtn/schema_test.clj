(ns openadr3.vtn.schema-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [openadr3.vtn.schema :as schema]
            [openadr3.vtn.storage :as store]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.storage.validated :as validated]
            [openadr3.vtn.handler.common :as common]))

(deftest notification-payload-test
  (testing "builds valid notification map"
    (let [program (common/add-metadata {:programName "PG&E-TOU"} "PROGRAM")
          payload (schema/notification-payload "PROGRAM" "CREATE" program)]
      (is (= "PROGRAM" (:objectType payload)))
      (is (= "CREATE" (:operation payload)))
      (is (= "PG&E-TOU" (get-in payload [:object :programName])))
      (is (nil? (schema/validate-notification-payload payload)))))

  (testing "invalid payload fails validation"
    (is (some? (schema/validate-notification-payload
                {:objectType "INVALID" :operation "CREATE" :object {}})))))

(deftest coerce-stored-program-test
  (testing "coerces a stored program to namespaced entity"
    (let [raw (common/add-metadata {:programName "test"} "PROGRAM")
          coerced (schema/coerce-stored raw)]
      (is (= "test" (:openadr.program/name coerced)))
      (is (= :openadr.object-type/program (:openadr/object-type coerced)))
      (is (string? (:openadr/id coerced)))
      (is (inst? (:openadr/created coerced))))))

(deftest coerce-stored-event-test
  (testing "coerces a stored event with intervals"
    (let [raw (common/add-metadata
               {:programID "p1"
                :eventName "price-signal"
                :priority 1
                :intervals [{:id 0
                             :payloads [{:type "PRICE" :values [0.25]}]}]}
               "EVENT")
          coerced (schema/coerce-stored raw)]
      (is (= :openadr.object-type/event (:openadr/object-type coerced)))
      (is (= "p1" (:openadr.event/program-id coerced)))
      (is (= 1 (:openadr.event/priority coerced)))
      (is (= 1 (count (:openadr.event/intervals coerced))))
      (let [interval (first (:openadr.event/intervals coerced))
            payload (first (:openadr.interval/payloads interval))]
        (is (= :openadr.payload-type/price (:openadr.payload/type payload)))
        (is (= [0.25M] (:openadr.payload/values payload)))))))

(deftest coerce-stored-subscription-test
  (testing "coerces a stored subscription"
    (let [raw (common/add-metadata
               {:programID "p1"
                :clientName "test-ven"
                :clientID "client-123"
                :objectOperations [{:objects ["EVENT"]
                                    :operations ["CREATE" "UPDATE"]
                                    :callbackUrl "http://example.com/callback"}]}
               "SUBSCRIPTION")
          coerced (schema/coerce-stored raw)]
      (is (= :openadr.object-type/subscription (:openadr/object-type coerced)))
      (is (= "test-ven" (:openadr.subscription/client-name coerced)))
      (is (= "p1" (:openadr.subscription/program-id coerced)))
      (let [op (first (:openadr.subscription/object-operations coerced))]
        (is (= [:openadr.object-type/event] (:openadr.object-operation/objects op)))
        (is (= [:openadr.operation/create :openadr.operation/update]
               (:openadr.object-operation/operations op)))))))

(deftest coerce-batch-test
  (testing "coerce-stored-programs handles multiple"
    (let [progs (mapv #(common/add-metadata {:programName (str "p" %)} "PROGRAM")
                      (range 3))
          coerced (schema/coerce-stored-programs progs)]
      (is (= 3 (count coerced)))
      (is (every? #(= :openadr.object-type/program (:openadr/object-type %)) coerced)))))

;; ---------------------------------------------------------------------------
;; Wire-format schema validation tests
;; ---------------------------------------------------------------------------

(defn- make-event
  "Build a valid stored event with intervalPeriod."
  [program-id]
  (common/add-metadata
   {:programID program-id
    :eventName "test-event"
    :intervalPeriod {:start "2025-01-15T00:00:00Z"
                     :duration "PT1H"}
    :intervals [{:id 0
                 :payloads [{:type "PRICE" :values [0.25]}]}]}
   "EVENT"))

(defn- make-subscription
  "Build a valid stored subscription."
  [program-id]
  (common/add-metadata
   {:programID program-id
    :clientName "test-client"
    :clientID "test-client"
    :objectOperations [{:objects ["EVENT"]
                        :operations ["CREATE" "UPDATE"]
                        :callbackUrl "https://example.com/callback"}]}
   "SUBSCRIPTION"))

(deftest wire-program-schema-test
  (testing "valid program passes"
    (let [p (common/add-metadata {:programName "Test"} "PROGRAM")]
      (is (nil? (m/explain schema/WireProgram p)))))

  (testing "missing programName fails"
    (is (some? (m/explain schema/WireProgram
                          (dissoc (common/add-metadata {:programName "x"} "PROGRAM")
                                  :programName)))))

  (testing "missing id fails"
    (is (some? (m/explain schema/WireProgram
                          (dissoc (common/add-metadata {:programName "x"} "PROGRAM")
                                  :id)))))

  (testing "missing createdDateTime fails"
    (is (some? (m/explain schema/WireProgram
                          (dissoc (common/add-metadata {:programName "x"} "PROGRAM")
                                  :createdDateTime)))))

  (testing "wrong objectType fails"
    (is (some? (m/explain schema/WireProgram
                          (assoc (common/add-metadata {:programName "x"} "PROGRAM")
                                 :objectType "EVENT")))))

  (testing "open schema allows extra keys"
    (is (nil? (m/explain schema/WireProgram
                         (assoc (common/add-metadata {:programName "x"} "PROGRAM")
                                :customField "ok"))))))

(deftest wire-event-schema-test
  (testing "valid event passes"
    (is (nil? (m/explain schema/WireEvent (make-event "p1")))))

  (testing "missing programID fails"
    (is (some? (m/explain schema/WireEvent
                          (dissoc (make-event "p1") :programID)))))

  (testing "missing intervalPeriod fails — the price-server bug"
    (is (some? (m/explain schema/WireEvent
                          (dissoc (make-event "p1") :intervalPeriod)))))

  (testing "intervalPeriod without :start fails"
    (is (some? (m/explain schema/WireEvent
                          (assoc (make-event "p1")
                                 :intervalPeriod {:duration "PT1H"})))))

  (testing "non-ISO start fails"
    (is (some? (m/explain schema/WireEvent
                          (assoc (make-event "p1")
                                 :intervalPeriod {:start "not-a-date"}))))))

(deftest wire-subscription-schema-test
  (testing "valid subscription passes"
    (is (nil? (m/explain schema/WireSubscription (make-subscription "p1")))))

  (testing "missing clientName fails"
    (is (some? (m/explain schema/WireSubscription
                          (dissoc (make-subscription "p1") :clientName)))))

  (testing "missing clientID fails"
    (is (some? (m/explain schema/WireSubscription
                          (dissoc (make-subscription "p1") :clientID)))))

  (testing "missing objectOperations fails"
    (is (some? (m/explain schema/WireSubscription
                          (dissoc (make-subscription "p1") :objectOperations))))))

;; ---------------------------------------------------------------------------
;; validate-entity! tests
;; ---------------------------------------------------------------------------

(deftest validate-entity-test
  (testing "valid entities return unchanged"
    (let [p (common/add-metadata {:programName "x"} "PROGRAM")]
      (is (= p (schema/validate-entity! p))))
    (let [e (make-event "p")]
      (is (= e (schema/validate-entity! e))))
    (let [s (make-subscription "p")]
      (is (= s (schema/validate-entity! s)))))

  (testing "invalid entity throws :validation-error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Entity validation failed"
                          (schema/validate-entity! (dissoc (make-event "p") :intervalPeriod)))))

  (testing "unknown objectType throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown objectType"
                          (schema/validate-entity! {:objectType "BOGUS"})))))

;; ---------------------------------------------------------------------------
;; Storage round-trip tests (ValidatingStorage wrapping AtomStorage)
;; ---------------------------------------------------------------------------

(def ^:dynamic *storage* nil)

(defn validated-storage-fixture [f]
  (let [raw (component/start (mem/new-atom-storage))
        vs  (validated/wrap-validation raw)]
    (binding [*storage* vs]
      (try (f)
           (finally (component/stop raw))))))

(use-fixtures :each validated-storage-fixture)

(deftest program-roundtrip-test
  (testing "program round-trips through validated storage"
    (let [original (common/add-metadata {:programName "RoundTrip Corp"} "PROGRAM")
          created  (store/create-program *storage* original)
          fetched  (store/get-program *storage* (:id created))]
      (is (= original created))
      (is (= original fetched))
      (is (nil? (m/explain schema/WireProgram fetched))))))

(deftest event-roundtrip-test
  (testing "event round-trips through validated storage"
    (let [original (make-event "prog-rt")
          created  (store/create-event *storage* original)
          fetched  (store/get-event *storage* (:id created))]
      (is (= original created))
      (is (= original fetched))
      (is (nil? (m/explain schema/WireEvent fetched))))))

(deftest subscription-roundtrip-test
  (testing "subscription round-trips through validated storage"
    (let [original (make-subscription "prog-rt")
          created  (store/create-subscription *storage* original)
          fetched  (store/get-subscription *storage* (:id created))]
      (is (= original created))
      (is (= original fetched))
      (is (nil? (m/explain schema/WireSubscription fetched))))))

;; ---------------------------------------------------------------------------
;; Validated storage rejects bad entities at boundary
;; ---------------------------------------------------------------------------

(deftest storage-rejects-invalid-entities-test
  (testing "rejects event missing intervalPeriod (the price-server bug)"
    (let [bad (common/add-metadata {:programID "p1" :eventName "bad"} "EVENT")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Entity validation failed"
                            (store/create-event *storage* bad)))))

  (testing "rejects program missing programName"
    (let [bad (common/add-metadata {} "PROGRAM")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Entity validation failed"
                            (store/create-program *storage* bad)))))

  (testing "rejects subscription missing objectOperations"
    (let [bad (common/add-metadata {:clientName "x" :clientID "x"} "SUBSCRIPTION")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Entity validation failed"
                            (store/create-subscription *storage* bad)))))

  (testing "bad entity never reaches storage"
    (let [bad (common/add-metadata {:programID "p1"} "EVENT")]
      (try (store/create-event *storage* bad) (catch Exception _))
      (is (empty? (store/list-events *storage* {}))))))
