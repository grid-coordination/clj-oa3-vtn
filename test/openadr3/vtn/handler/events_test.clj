(ns openadr3.vtn.handler.events-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [openadr3.vtn.storage :as store]
            [openadr3.vtn.storage.memory :as mem]
            [openadr3.vtn.handler.common :as common]
            [openadr3.vtn.handler.events :as events]
            [openadr3.vtn.time :as vtn-time])
  (:import [java.time Duration ZonedDateTime]))

(def ^:dynamic *storage* nil)

(defn storage-fixture [f]
  (binding [*storage* (component/start (mem/new-atom-storage))]
    (try (f)
         (finally (component/stop *storage*)))))

(use-fixtures :each storage-fixture)

(defn- invoke [handler-fn & [request-overrides]]
  (let [request (merge {:query-params {} :path-params {} :body {}} request-overrides)]
    ((handler-fn *storage*) request)))

(deftest create-and-get-test
  (let [prog (store/create-program *storage* (common/add-metadata {:programName "p1"} "PROGRAM"))
        resp (invoke events/create {:body {:programID (:id prog) :eventName "e1"}})]

    (testing "create returns 201"
      (is (= 201 (:status resp)))
      (is (= "EVENT" (get-in resp [:body :objectType]))))

    (testing "get by id"
      (let [id (get-in resp [:body :id])
            get-resp (invoke events/get-by-id {:path-params {:eventID id}})]
        (is (= 200 (:status get-resp)))
        (is (= (:id prog) (get-in get-resp [:body :programID])))))))

(deftest create-event-with-price-intervals-test
  (let [prog (store/create-program *storage*
                                   (common/add-metadata
                                    {:programName "price-prog"
                                     :payloadDescriptors [{:objectType "EVENT_PAYLOAD_DESCRIPTOR"
                                                           :payloadType "PRICE"
                                                           :units "KWH"
                                                           :currency "USD"}]}
                                    "PROGRAM"))
        resp (invoke events/create
                     {:body {:programID (:id prog)
                             :eventName "hourly-prices"
                             :intervals [{:id 0
                                          :payloads [{:type "PRICE" :values [0.25]}]}
                                         {:id 1
                                          :payloads [{:type "PRICE" :values [0.35]}]}]}})]

    (testing "create returns 201 with intervals"
      (is (= 201 (:status resp)))
      (is (= 2 (count (get-in resp [:body :intervals])))))

    (testing "round-trips through get"
      (let [id (get-in resp [:body :id])
            fetched (:body (invoke events/get-by-id {:path-params {:eventID id}}))]
        (is (= 0.25 (get-in fetched [:intervals 0 :payloads 0 :values 0])))
        (is (= 0.35 (get-in fetched [:intervals 1 :payloads 0 :values 0])))))))

(deftest search-with-programID-filter-test
  (let [p1 (store/create-program *storage* (common/add-metadata {:programName "p1"} "PROGRAM"))
        p2 (store/create-program *storage* (common/add-metadata {:programName "p2"} "PROGRAM"))]
    (invoke events/create {:body {:programID (:id p1) :eventName "e1"}})
    (invoke events/create {:body {:programID (:id p1) :eventName "e2"}})
    (invoke events/create {:body {:programID (:id p2) :eventName "e3"}})

    (testing "filter by programID"
      (let [resp (invoke events/search-all {:query-params {:programID (:id p1)}})]
        (is (= 2 (count (:body resp))))))

    (testing "no filter returns all"
      (is (= 3 (count (:body (invoke events/search-all))))))))

(deftest update-and-delete-test
  (let [prog (store/create-program *storage* (common/add-metadata {:programName "ud-prog"} "PROGRAM"))
        resp (invoke events/create {:body {:programID (:id prog) :eventName "orig"}})
        id (get-in resp [:body :id])]

    (testing "update"
      (let [upd (invoke events/update-by-id
                        {:path-params {:eventID id}
                         :body {:programID (:id prog) :eventName "changed" :priority 1}})]
        (is (= 200 (:status upd)))
        (is (= "changed" (get-in upd [:body :eventName])))))

    (testing "delete"
      (is (= 200 (:status (invoke events/delete-by-id {:path-params {:eventID id}}))))
      (is (= 404 (:status (invoke events/get-by-id {:path-params {:eventID id}})))))))

(deftest event-storage-canon-test
  (let [prog (store/create-program *storage* (common/add-metadata {:programName "tz-prog"} "PROGRAM"))
        resp (invoke events/create
                     {:body {:programID (:id prog)
                             :eventName "tz-event"
                             :intervalPeriod {:start "2026-05-03T00:00:00-07:00"
                                              :duration "PT1H"}}})]

    (testing "stored event holds ZonedDateTime / Duration (storage canon)"
      (is (= 201 (:status resp)))
      (is (instance? ZonedDateTime (get-in resp [:body :createdDateTime])))
      (is (instance? ZonedDateTime (get-in resp [:body :intervalPeriod :start])))
      (is (instance? Duration (get-in resp [:body :intervalPeriod :duration]))))

    (testing "wire offset is preserved on the in-memory ZDT"
      (let [start (get-in resp [:body :intervalPeriod :start])]
        (is (= "-07:00" (str (.getZone ^ZonedDateTime start))))))))

;; ---------------------------------------------------------------------------
;; Default filter — zone-neutral overlap [now, now+2d]
;; ---------------------------------------------------------------------------

(defn- create-event-at
  "Create an event whose intervalPeriod is [start, start+duration]."
  [program-id start duration]
  (invoke events/create
          {:body {:programID program-id
                  :eventName (str "ev-" (System/nanoTime))
                  :intervalPeriod {:start start
                                   :duration duration}}}))

(deftest default-window-overlap-test
  (let [prog (store/create-program *storage*
                                   (common/add-metadata {:programName "win-prog"} "PROGRAM"))
        pid  (:id prog)
        now  (vtn-time/now-zdt)
        ;; helpers: minus seconds = ZDT in past, plus seconds = ZDT in future
        minus (fn [n] (.minusSeconds now n))
        plus  (fn [n] (.plusSeconds now n))]

    (testing "active-now event is included"
      ;; Started 1h ago, runs 2h — currently active
      (let [e (:body (create-event-at pid (minus 3600) "PT2H"))
            resp (invoke events/search-all {:query-params {}})
            ids  (set (map :id (:body resp)))]
        (is (contains? ids (:id e)))))

    (testing "near-future event (within next 2d) is included"
      ;; Starts in 30h, runs 1h
      (let [e (:body (create-event-at pid (plus (* 30 3600)) "PT1H"))
            resp (invoke events/search-all {:query-params {}})
            ids  (set (map :id (:body resp)))]
        (is (contains? ids (:id e)))))

    (testing "completed event (ended 1h ago) is excluded"
      (let [e (:body (create-event-at pid (minus (* 3 3600)) "PT2H"))
            resp (invoke events/search-all {:query-params {}})
            ids  (set (map :id (:body resp)))]
        (is (not (contains? ids (:id e))))))

    (testing "far-future event (starts in 5d) is excluded"
      (let [e (:body (create-event-at pid (plus (* 5 24 3600)) "PT1H"))
            resp (invoke events/search-all {:query-params {}})
            ids  (set (map :id (:body resp)))]
        (is (not (contains? ids (:id e))))))))

(deftest explicit-range-test
  (testing "explicit dateStart/dateEnd uses BETWEEN on intervalPeriod.start"
    (let [prog (store/create-program *storage*
                                     (common/add-metadata {:programName "exp-prog"} "PROGRAM"))
          pid  (:id prog)
          ;; Use far-future to ensure overlap default would NOT include them
          base (vtn-time/parse-zdt "2030-01-01T00:00:00Z")
          inside (.plusHours base 12)
          before (.minusDays base 1)]
      (create-event-at pid inside "PT1H")
      (create-event-at pid before "PT1H")

      (let [resp (invoke events/search-all
                         {:query-params {"dateStart" "2030-01-01T00:00:00Z"
                                         "dateEnd"   "2030-01-02T00:00:00Z"}})
            ids  (map :id (:body resp))]
        (is (= 1 (count ids)))))))
