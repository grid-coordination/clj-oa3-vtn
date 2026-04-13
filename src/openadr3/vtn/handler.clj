(ns openadr3.vtn.handler
  "Legba routing-handler assembly.

  Builds two handler maps — one for the BL port (full CRUD) and one for
  the VEN port (read + subscribe) — from the OpenAPI spec."
  (:require [s-exp.legba :as legba]
            [s-exp.legba.json :as legba-json]
            [clojure.java.io :as io]
            [openadr3.vtn.handler.programs :as programs]
            [openadr3.vtn.handler.events :as events]
            [openadr3.vtn.handler.subscriptions :as subs]
            [openadr3.vtn.handler.notifiers :as notifiers]
            [openadr3.vtn.handler.topics :as topics]
            [openadr3.vtn.handler.auth :as auth]))

(defn- load-spec-json
  "Load the OpenAPI YAML spec from classpath and convert to JSON string."
  []
  (-> (io/resource "openadr3.yaml")
      slurp
      legba-json/yaml-str->json-str))

(defn bl-handler-map
  "Build the BL port handler map — full CRUD on programs, events, subscriptions."
  [storage notifier config]
  {[:get "/programs"]                          (programs/search-all storage)
   [:post "/programs"]                         (programs/create storage notifier)
   [:get "/programs/{programID}"]              (programs/get-by-id storage)
   [:put "/programs/{programID}"]              (programs/update-by-id storage notifier)
   [:delete "/programs/{programID}"]           (programs/delete-by-id storage notifier)

   [:get "/events"]                            (events/search-all storage)
   [:post "/events"]                           (events/create storage notifier)
   [:get "/events/{eventID}"]                  (events/get-by-id storage)
   [:put "/events/{eventID}"]                  (events/update-by-id storage notifier)
   [:delete "/events/{eventID}"]               (events/delete-by-id storage notifier)

   [:get "/subscriptions"]                     (subs/search-all storage)
   [:post "/subscriptions"]                    (subs/create storage notifier)
   [:get "/subscriptions/{subscriptionID}"]    (subs/get-by-id storage)
   [:put "/subscriptions/{subscriptionID}"]    (subs/update-by-id storage notifier)
   [:delete "/subscriptions/{subscriptionID}"] (subs/delete-by-id storage notifier)

   [:get "/notifiers"]                         (notifiers/list-all config (:bl-notifiers config))
   [:get "/notifiers/mqtt/topics/programs"]                        (topics/programs-topics)
   [:get "/notifiers/mqtt/topics/programs/{programID}"]            (topics/program-topics)
   [:get "/notifiers/mqtt/topics/programs/{programID}/events"]     (topics/program-events-topics)
   [:get "/notifiers/mqtt/topics/events"]                          (topics/events-topics)
   [:get "/notifiers/mqtt/topics/reports"]                         (topics/reports-topics)
   [:get "/notifiers/mqtt/topics/subscriptions"]                   (topics/subscriptions-topics)
   [:get "/notifiers/mqtt/topics/vens"]                            (topics/vens-topics)
   [:get "/notifiers/mqtt/topics/vens/{venID}"]                    (topics/ven-topics)
   [:get "/notifiers/mqtt/topics/vens/{venID}/events"]             (topics/ven-events-topics)
   [:get "/notifiers/mqtt/topics/vens/{venID}/programs"]           (topics/ven-programs-topics)
   [:get "/notifiers/mqtt/topics/vens/{venID}/resources"]          (topics/ven-resources-topics)
   [:get "/notifiers/mqtt/topics/resources"]                       (topics/resources-topics)

   [:get "/auth/server"]                       (auth/server-info config)
   [:post "/auth/token"]                       (auth/fetch-token)})

(def ^:private default-ven-routes
  "Default VEN port route enablement. Programs and events are read-only
   (safe for anonymous access). Everything else is disabled."
  {:programs      :read-only
   :events        :read-only
   :subscriptions false
   :vens          false
   :resources     false
   :reports       false})

(defn ven-handler-map
  "Build the VEN port handler map based on :ven-routes config.

   Each resource type can be:
     :read-only — GET routes only (safe for anonymous access)
     :full      — full CRUD (for authenticated VEN deployments)
     false/nil  — disabled (routes omitted, returns 404/405)

   Disabled resources also have their topic discovery routes suppressed.
   Notifier is passed only for :full resources; :read-only gets nil."
  [storage notifier config]
  (let [routes   (merge default-ven-routes (:ven-routes config))
        enabled? (fn [k] (get routes k))
        full?    (fn [k] (= :full (get routes k)))
        nfr      (fn [k] (when (full? k) notifier))]
    (merge
     ;; --- Programs ---
     (when (enabled? :programs)
       (merge
        {[:get "/programs"]             (programs/search-all storage)
         [:get "/programs/{programID}"] (programs/get-by-id storage)}
        (when (full? :programs)
          {[:post "/programs"]               (programs/create storage (nfr :programs))
           [:put "/programs/{programID}"]    (programs/update-by-id storage (nfr :programs))
           [:delete "/programs/{programID}"] (programs/delete-by-id storage (nfr :programs))})))

     ;; --- Events ---
     (when (enabled? :events)
       (merge
        {[:get "/events"]           (events/search-all storage)
         [:get "/events/{eventID}"] (events/get-by-id storage)}
        (when (full? :events)
          {[:post "/events"]              (events/create storage (nfr :events))
           [:put "/events/{eventID}"]     (events/update-by-id storage (nfr :events))
           [:delete "/events/{eventID}"]  (events/delete-by-id storage (nfr :events))})))

     ;; --- Subscriptions ---
     (when (enabled? :subscriptions)
       (merge
        {[:get "/subscriptions"]                  (subs/search-all storage)
         [:get "/subscriptions/{subscriptionID}"] (subs/get-by-id storage)}
        (when (full? :subscriptions)
          {[:post "/subscriptions"]                    (subs/create storage (nfr :subscriptions))
           [:put "/subscriptions/{subscriptionID}"]    (subs/update-by-id storage (nfr :subscriptions))
           [:delete "/subscriptions/{subscriptionID}"] (subs/delete-by-id storage (nfr :subscriptions))})))

     ;; --- Notifiers & topic discovery (filtered by enabled resources) ---
     {[:get "/notifiers"] (notifiers/list-all config (:ven-notifiers config))}

     (when (enabled? :programs)
       {[:get "/notifiers/mqtt/topics/programs"]             (topics/programs-topics)
        [:get "/notifiers/mqtt/topics/programs/{programID}"] (topics/program-topics)})
     (when (enabled? :events)
       (merge
        {[:get "/notifiers/mqtt/topics/events"] (topics/events-topics)}
        (when (enabled? :programs)
          {[:get "/notifiers/mqtt/topics/programs/{programID}/events"] (topics/program-events-topics)})))
     (when (enabled? :subscriptions)
       {[:get "/notifiers/mqtt/topics/subscriptions"] (topics/subscriptions-topics)})
     (when (enabled? :reports)
       {[:get "/notifiers/mqtt/topics/reports"] (topics/reports-topics)})
     (when (enabled? :vens)
       (merge
        {[:get "/notifiers/mqtt/topics/vens"]            (topics/vens-topics)
         [:get "/notifiers/mqtt/topics/vens/{venID}"]    (topics/ven-topics)}
        (when (enabled? :events)
          {[:get "/notifiers/mqtt/topics/vens/{venID}/events"] (topics/ven-events-topics)})
        (when (enabled? :programs)
          {[:get "/notifiers/mqtt/topics/vens/{venID}/programs"] (topics/ven-programs-topics)})
        (when (enabled? :resources)
          {[:get "/notifiers/mqtt/topics/vens/{venID}/resources"] (topics/ven-resources-topics)})))
     (when (enabled? :resources)
       {[:get "/notifiers/mqtt/topics/resources"] (topics/resources-topics)})

     ;; --- Auth (always on) ---
     {[:get "/auth/server"]  (auth/server-info config)
      [:post "/auth/token"]  (auth/fetch-token)})))

(defn make-routing-handler
  "Create a Legba routing-handler from a handler map and the OpenAPI spec."
  [handler-map]
  (let [spec-json (load-spec-json)]
    (legba/routing-handler handler-map spec-json
                           {:schema-src-type :string
                            :key-fn keyword})))
