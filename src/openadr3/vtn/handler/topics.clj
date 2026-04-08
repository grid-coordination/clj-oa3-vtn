(ns openadr3.vtn.handler.topics
  "MQTT topic discovery handlers (/notifiers/mqtt/topics/*).")

(def ^:private topic-prefix "OpenADR/3.1.0")

(defn- operations-topics
  "Build a notifierOperationsTopics response for a given base path.
   For collection-level topics (no specific ID), include CREATE.
   For object-level topics (specific ID), omit CREATE."
  [base-path & {:keys [include-create] :or {include-create true}}]
  (let [topics (cond-> {:UPDATE (str base-path "/update")
                        :DELETE (str base-path "/delete")
                        :ALL    (str base-path "/+")}
                 include-create (assoc :CREATE (str base-path "/create")))]
    {:status 200
     :body {:topics topics}}))

;; --- Collection-level topics (all objects) ---

(defn programs-topics
  "GET /notifiers/mqtt/topics/programs"
  []
  (fn [_request]
    (operations-topics (str topic-prefix "/programs"))))

(defn events-topics
  "GET /notifiers/mqtt/topics/events"
  []
  (fn [_request]
    (operations-topics (str topic-prefix "/events"))))

(defn reports-topics
  "GET /notifiers/mqtt/topics/reports"
  []
  (fn [_request]
    (operations-topics (str topic-prefix "/reports"))))

(defn subscriptions-topics
  "GET /notifiers/mqtt/topics/subscriptions"
  []
  (fn [_request]
    (operations-topics (str topic-prefix "/subscriptions"))))

(defn vens-topics
  "GET /notifiers/mqtt/topics/vens"
  []
  (fn [_request]
    (operations-topics (str topic-prefix "/vens"))))

(defn resources-topics
  "GET /notifiers/mqtt/topics/resources"
  []
  (fn [_request]
    (operations-topics (str topic-prefix "/resources"))))

;; --- Object-level topics (specific ID) ---

(defn program-topics
  "GET /notifiers/mqtt/topics/programs/{programID}"
  []
  (fn [request]
    (let [pid (get-in request [:path-params :programID])]
      (operations-topics (str topic-prefix "/programs/" pid)
                         :include-create false))))

(defn program-events-topics
  "GET /notifiers/mqtt/topics/programs/{programID}/events"
  []
  (fn [request]
    (let [pid (get-in request [:path-params :programID])]
      (operations-topics (str topic-prefix "/programs/" pid "/events")))))

(defn ven-topics
  "GET /notifiers/mqtt/topics/vens/{venID}"
  []
  (fn [request]
    (let [vid (get-in request [:path-params :venID])]
      (operations-topics (str topic-prefix "/vens/" vid)
                         :include-create false))))

(defn ven-events-topics
  "GET /notifiers/mqtt/topics/vens/{venID}/events"
  []
  (fn [request]
    (let [vid (get-in request [:path-params :venID])]
      (operations-topics (str topic-prefix "/vens/" vid "/events")))))

(defn ven-programs-topics
  "GET /notifiers/mqtt/topics/vens/{venID}/programs"
  []
  (fn [request]
    (let [vid (get-in request [:path-params :venID])]
      (operations-topics (str topic-prefix "/vens/" vid "/programs")))))

(defn ven-resources-topics
  "GET /notifiers/mqtt/topics/vens/{venID}/resources"
  []
  (fn [request]
    (let [vid (get-in request [:path-params :venID])]
      (operations-topics (str topic-prefix "/vens/" vid "/resources")))))
