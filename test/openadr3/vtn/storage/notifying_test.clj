(ns openadr3.vtn.storage.notifying-test
  "Tests that NotifyingStorage publishes MQTT notifications on C/U/D.
   Uses a recording stub notifier to capture what would be published."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as store]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.storage.notifying :as notifying]
            [openadr3.vtn.notifier]
            [openadr3.vtn.handler.common :as common]))

;; ---------------------------------------------------------------------------
;; Recording stub notifier — captures notify! calls
;; ---------------------------------------------------------------------------

(def ^:dynamic *notifications* nil)
(def ^:dynamic *storage* nil)

(defrecord RecordingNotifier [notifications-atom]
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

;; Override notify! to record instead of publish to MQTT
;; notify! checks (when notifier ...) so the record must be truthy
(defn recording-notifier []
  (->RecordingNotifier (atom [])))

;; Monkey-patch notify! for test isolation — record calls
(defn install-recording-notify! [_notifier]
  (let [orig-notify @#'openadr3.vtn.notifier/notify!]
    (alter-var-root #'openadr3.vtn.notifier/notify!
                    (fn [_]
                      (fn [n object-type operation object]
                        (when n
                          (swap! (:notifications-atom n)
                                 conj {:object-type object-type
                                       :operation operation
                                       :id (:id object)})))))
    orig-notify))

;; Actually, notify! is a regular defn, not a multimethod. Let's use a simpler approach:
;; Wire the NotifyingStorage with a notifier that has :mqtt-publisher set to nil.
;; notify! is nil-safe (when notifier ...) but we need it to actually record.
;; Simplest: just use with-redefs.

(defn storage-fixture [f]
  (let [raw      (component/start (mem/new-atom-storage))
        notifier (recording-notifier)
        ns       (notifying/->NotifyingStorage raw notifier)]
    (binding [*storage* ns
              *notifications* (:notifications-atom notifier)]
      (with-redefs [openadr3.vtn.notifier/notify!
                    (fn [n object-type operation object]
                      (when n
                        (swap! (:notifications-atom n)
                               conj {:object-type object-type
                                     :operation operation
                                     :id (:id object)})))]
        (try (f)
             (finally (component/stop raw)))))))

(use-fixtures :each storage-fixture)

(defn- notifications []
  @*notifications*)

(defn- clear-notifications! []
  (reset! *notifications* []))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn- make-program [name]
  (common/add-metadata {:programName name} "PROGRAM"))

(defn- make-event [program-id]
  (common/add-metadata
   {:programID program-id
    :eventName "test-event"
    :intervalPeriod {:start "2025-01-15T00:00:00Z" :duration "PT1H"}
    :intervals [{:id 0 :payloads [{:type "PRICE" :values [0.25]}]}]}
   "EVENT"))

(defn- make-subscription [program-id]
  (common/add-metadata
   {:programID program-id
    :clientName "test-client"
    :clientID "test-client"
    :objectOperations [{:objects ["EVENT"] :operations ["CREATE"]}]}
   "SUBSCRIPTION"))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest create-publishes-notification-test
  (testing "create-program publishes CREATE notification"
    (let [p (store/create-program *storage* (make-program "notify-test"))]
      (is (= 1 (count (notifications))))
      (is (= {:object-type "PROGRAM" :operation "CREATE" :id (:id p)}
             (first (notifications))))))

  (clear-notifications!)

  (testing "create-event publishes CREATE notification"
    (let [e (store/create-event *storage* (make-event "prog-1"))]
      (is (= 1 (count (notifications))))
      (is (= {:object-type "EVENT" :operation "CREATE" :id (:id e)}
             (first (notifications))))))

  (clear-notifications!)

  (testing "create-subscription publishes CREATE notification"
    (let [s (store/create-subscription *storage* (make-subscription "prog-1"))]
      (is (= 1 (count (notifications))))
      (is (= {:object-type "SUBSCRIPTION" :operation "CREATE" :id (:id s)}
             (first (notifications)))))))

(deftest update-publishes-notification-test
  (let [p (store/create-program *storage* (make-program "upd-test"))]
    (clear-notifications!)

    (testing "update-program publishes UPDATE notification"
      (let [updated (common/touch-metadata p {:programName "updated"})
            _stored (store/update-program *storage* (:id p) updated)]
        (is (= 1 (count (notifications))))
        (is (= {:object-type "PROGRAM" :operation "UPDATE" :id (:id p)}
               (first (notifications))))))

    (clear-notifications!)

    (testing "update nonexistent does not notify"
      (store/update-program *storage* "no-such" (make-program "x"))
      (is (empty? (notifications))))))

(deftest delete-publishes-notification-test
  (let [p (store/create-program *storage* (make-program "del-test"))]
    (clear-notifications!)

    (testing "delete-program publishes DELETE notification"
      (store/delete-program *storage* (:id p))
      (is (= 1 (count (notifications))))
      (is (= {:object-type "PROGRAM" :operation "DELETE" :id (:id p)}
             (first (notifications)))))

    (clear-notifications!)

    (testing "delete nonexistent does not notify"
      (store/delete-program *storage* "no-such")
      (is (empty? (notifications))))))

(deftest direct-storage-writes-notify-test
  (testing "writes bypassing HTTP handlers still produce notifications"
    (let [_e (store/create-event *storage* (make-event "direct-write"))]
      (is (= 1 (count (filter #(= "EVENT" (:object-type %)) (notifications))))
          "Direct storage write should trigger notification — this is the fetcher use case"))))
