(ns onyx.http-query
  (:require [onyx.static.default-vals :refer [arg-or-default]]
            [ring.component.jetty :refer [jetty-server]]
            [ring.middleware.params :refer [wrap-params]]
            [com.stuartsierra.component :as component]
            [cheshire.core :refer [generate-string]]
            [clojure.java.jmx :as jmx]
            [onyx.query]
            [onyx.state.protocol.db :as db]
            [onyx.peer-query.job-query :as jq]
            [onyx.system :as system]
            [onyx.peer-query.aeron]
            [onyx.metrics-endpoint :as metrics-endpoint]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [info error infof]]))

(def default-serializer "application/edn")

(def parse-uuid #(java.util.UUID/fromString %))

(defn parse-keyword [kw-str]
  (keyword (clojure.string/replace kw-str #"^:" "")))

(def parsers 
  {:keyword parse-keyword
   :long (fn [s] (if-not (empty? s) (Long/parseLong s)))
   :string identity
   :uuid parse-uuid})

(defn get-param [request param coercer]
  (try 
   ((get parsers coercer) (or (get-in request [:query-params param])
                              (throw (Exception. (str "Missing param " param)))))
   (catch Throwable t
     (throw (Exception. (str "Failed to parse param " param " as " coercer))))))

(defn fetch-from-zookeeper [peer-config f]
  (let [client (component/start (system/onyx-client peer-config))] 
    (try 
     (f (:log client))
     (finally (component/stop client)))))

(defn time-since-heartbeat []
  (when-let [jbean (first (jmx/mbean-names "org.onyxplatform:name=peer-group.since-heartbeat"))]
    (jmx/read jbean "Value")))

(defn unwrap-grouped-contents [grouped? contents]
  (if grouped? 
    contents 
    (first (vals contents))))

(def default-healthy-heartbeat-timeout 40000)

(def endpoints
  {{:uri "/network/media-driver"
    :request-method :get}
   {:doc "Returns a map describing the media driver status."
    :f (fn [request _ _ _] (onyx.peer-query.aeron/media-driver-health))}

   {:uri "/network/media-driver/active"
    :request-method :get}
   {:doc "Returns a boolean for whether the media driver is healthy and heartbeating."
    :f (fn [request _ _ _] 
         (let [active (:active (onyx.peer-query.aeron/media-driver-health))]
           {:status (if active 200 500)
            :result active}))}

   {:uri "/health"
    :request-method :get}
   {:doc "Single health check call to check whether the following statuses are healthy: /network/media-driver/active, /peergroup/heartbeat. Call with /health?threshold=30000 for a peer-group heartbeat timeout."
    :f (fn [request _ _ _]
         (let [time-since (time-since-heartbeat)
               threshold (or (get-param request "threshold" :long) default-healthy-heartbeat-timeout)
               pg-healthy? (< time-since threshold)
               media-driver-healthy? (:active (onyx.peer-query.aeron/media-driver-health))
               total-healthy? (and pg-healthy? media-driver-healthy?)]
           {:status (if total-healthy? 200 500)
            :result total-healthy?}))}

   {:uri "/peergroup/heartbeat"
    :request-method :get}
   {:doc "Returns the number of milliseconds since the last peer group heartbeat."
    :f (fn [request _ _ _]
         {:result (time-since-heartbeat)})}
   
   {:uri "/peergroup/health"
    :request-method :get}
   {:doc "Returns the number of milliseconds since the last peer group heartbeat."
    :f (fn [request _ _ _] 
         (let [time-since (time-since-heartbeat)
               threshold (or (get-param request "threshold" :long) default-healthy-heartbeat-timeout)
               healthy? (< time-since threshold)]
           {:status (if healthy? 200 500)
            :result healthy?}))}
 
   {:uri "/metrics"
    :request-method :get}
   {:doc "Returns metrics for prometheus"
    :f (fn [request peer-config _ _]
         {:result (metrics-endpoint/metrics-endpoint peer-config)})}

   {:uri "/replica"
    :request-method :get}
   {:doc "Returns a snapshot of the replica"
    :f (fn [request _ replica _]
         {:result replica})}

   {:uri "/replica/peers"
    :request-method :get}
   {:doc "Lists all the peer ids"
    :f (fn [request _ replica _]
         {:result (:peers replica)})}

   {:uri "/replica/jobs"
    :request-method :get}
   {:doc "Lists all non-killed, non-completed job ids."
    :f (fn [request _ replica _]
         {:result (:jobs replica)})}

   {:uri "/replica/killed-jobs"
    :request-method :get}
   {:doc "Lists all the job ids that have been killed."
    :f (fn [request _ replica _]
         {:result (:killed-jobs replica)})}

   {:uri "/replica/completed-jobs"
    :request-method :get}
   {:doc "Lists all the job ids that have been completed."
    :f (fn [request _ replica _]
         {:result (:completed-jobs replica)})}

   {:uri "/replica/tasks"
    :request-method :get}
   {:doc "Given a job id, returns all the task ids for this job."
    :query-params-schema {"job-id" String}
    :f (fn [request _ replica _]
         (let [job-id (get-param request "job-id" :uuid)]
           {:result (get-in replica [:tasks job-id])}))}

   {:uri "/replica/job-allocations"
    :request-method :get}
   {:doc "Returns a map of job id -> task id -> peer ids, denoting which peers are assigned to which tasks."
    :f (fn [request _ replica _]
         {:result (:allocations replica)})}

   {:uri "/replica/task-allocations"
    :request-method :get}
   {:doc "Given a job id, returns a map of task id -> peer ids, denoting which peers are assigned to which tasks for this job only."
    :f (fn [request _ replica _]
         (let [job-id (get-param request "job-id" :uuid)]
           {:result (get-in replica [:allocations job-id])}))}

   {:uri "/replica/peer-site"
    :request-method :get}
   {:doc "Given a peer id, returns the Aeron hostname and port that this peer advertises to the rest of the cluster."
    :query-params-schema {"peer-id" String}
    :f (fn [request _ replica _]
         (let [peer-id (get-param request "peer-id" :uuid)]
           {:result (get-in replica [:peer-sites peer-id])}))}

   {:uri "/replica/peer-state"
    :request-method :get}
   {:doc "Given a peer id, returns its current execution state (e.g. :idle, :active, etc)."
    :query-params-schema {"peer-id" String}
    :f (fn [request _ replica _]
         (let [peer-id (get-param request "peer-id" :uuid)]
           {:result (get-in replica [:peer-state peer-id])}))}

   {:uri "/replica/job-scheduler"
    :request-method :get}
   {:doc "Returns the job scheduler for this tenancy of the cluster."
    :f (fn [request _ replica _]
         {:result (:job-scheduler replica)})}

   {:uri "/replica/task-scheduler"
    :request-method :get}
   {:doc "Given a job id, returns the task scheduler for this job."
    :query-params-schema
    {"job-id" String}
    :f (fn [request _ replica _]
         {:result (let [job-id (get-param request "job-id" :uuid)]
                    (get-in replica [:task-schedulers job-id]))})}

   {:uri "/replica/allocation-version"
    :request-method :get}
   {:doc "Given a job id, returns the task scheduler for this job."
    :query-params-schema
    {"job-id" String}
    :f (fn [request _ replica _]
         {:result (let [job-id (get-param request "job-id" :uuid)]
                    (get-in replica [:allocation-version job-id]))})}

   {:uri "/state-entries"
    :request-method :get}
   {:doc "Retrieve a task's window state entries for a particular job. Must supply the :allocation-version for the job. 
          The allocation version can be looked up via the /replica/allocation-version, or by subscribing to the log and looking up the [:allocation-version job-id]."
    :query-params-schema {"job-id" String 
                          "task-id" String
                          "slot-id" Long
                          "allocation-version" Long
                          "window-id" String ; or UUID
                          "start-time" Long
                          "end-time" Long}
    :f (fn [request peer-config replica state-store-group]
         (let [allocation-version (get-param request "allocation-version" :long)
               job-id (get-param request "job-id" :uuid)
               task (get-param request "task-id" :keyword)
               window (or (try (get-param request "window-id" :uuid)
                               (catch Throwable _))
                          (get-param request "window-id" :keyword))
               slot-id (get-param request "slot-id" :long)
               start-time (get-param request "start-time" :long)
               end-time (get-param request "end-time" :long)
               store (get @(:state state-store-group) [job-id task slot-id allocation-version])
               _ (when-not store (throw (ex-info "Peer state store not found." {})))
               {:keys [db state-indices grouped? idx->window]} store
               idx (get state-indices window)
               group (get-in request [:query-params "group"])
               groups (if group
                        [(clojure.edn/read-string group)]
                        (db/groups db))]
           {:result {:grouped? grouped? 
                     :window (get idx->window idx)
                     :contents (->> groups
                                    (reduce (fn [m group]
                                              (let [group-id (db/group-id db group)] 
                                                (assoc m group (db/get-state-entries db idx group-id start-time end-time))))
                                            {})
                                    (unwrap-grouped-contents grouped?))}}))}

   {:uri "/state"
    :request-method :get}
   {:doc "Retrieve a task's window state for a particular job. Must supply the :allocation-version for the job. 
          The allocation version can be looked up via the /replica/allocation-version, or by subscribing to the log and looking up the [:allocation-version job-id]."
    :query-params-schema {"job-id" String 
                          "task-id" String
                          "slot-id" Long
                          "group" String
                          "window-id" String ; or UUID
                          "allocation-version" Long}
    :f (fn [request peer-config replica state-store-group]
         (let [allocation-version (get-param request "allocation-version" :long)
               job-id (get-param request "job-id" :uuid)
               task (get-param request "task-id" :keyword)
               window (or (try (get-param request "window-id" :uuid)
                               (catch Throwable _))
                          (get-param request "window-id" :keyword))
               slot-id (get-param request "slot-id" :long)
               store (get @(:state state-store-group) [job-id task slot-id allocation-version])
               {:keys [db state-indices grouped? idx->window]} store
               group (get-in request [:query-params "group"])
               groups (if group
                        [(clojure.edn/read-string group)]
                        (db/groups db))
               _ (when-not store (throw (ex-info "Peer state store not found." {})))
               idx (get state-indices window)]
           {:result {:grouped? grouped? 
                     :window (get idx->window idx)
                     :contents (->> groups
                                    (reduce (fn [m group]
                                              (let [group-id (db/group-id db group)] 
                                                (reduce (fn [m extent]
                                                          (update m 
                                                                  group 
                                                                  (fn [m] 
                                                                    (conj (or m [])
                                                                          [extent (db/get-extent db idx group-id extent)]))))
                                                        m
                                                        (db/group-extents db idx group-id))))
                                            {})
                                    (unwrap-grouped-contents grouped?))}}))}

   {:uri "/job/workflow"
    :request-method :get}
   {:doc (:doc (meta #'jq/workflow))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica _]
         (fetch-from-zookeeper 
          peer-config
          (fn [log] 
            (let [job-id (get-param request "job-id" :uuid)]
              (jq/workflow log job-id)))))}

   {:uri "/job/catalog"
    :request-method :get}
   {:doc (:doc (meta #'jq/catalog))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica _]
         {:result (fetch-from-zookeeper 
                   peer-config
                   (fn [log] 
                     (let [job-id (get-param request "job-id" :uuid)]
                       (jq/catalog log job-id))))})}

   {:uri "/job/flow-conditions"
    :request-method :get}
   {:doc (:doc (meta #'jq/flow-conditions))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica _]
         {:result (fetch-from-zookeeper 
                   peer-config
                   (fn [log] 
                     (let [job-id (get-param request "job-id" :uuid)]
                       (jq/flow-conditions log job-id))))})}

   {:uri "/job/lifecycles"
    :request-method :get}
   {:doc (:doc (meta #'jq/lifecycles))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica _]
         {:result (fetch-from-zookeeper 
                   peer-config
                   (fn [log] 
                     (let [job-id (get-param request "job-id" :uuid)]
                       (jq/lifecycles log job-id))))})}

   {:uri "/job/windows"
    :request-method :get}
   {:doc (:doc (meta #'jq/windows))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica _]
         {:result (fetch-from-zookeeper 
                   peer-config
                   (fn [log] 
                     (let [job-id (get-param request "job-id" :uuid)]
                       (jq/windows log job-id))))})}

   {:uri "/job/triggers"
    :request-method :get}
   {:doc (:doc (meta #'jq/triggers))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica _]
         {:result (fetch-from-zookeeper 
                   peer-config
                   (fn [log] 
                     (let [job-id (get-param request "job-id" :uuid)]
                       (jq/triggers log job-id))))})}

   {:uri "/job/exception"
    :request-method :get}
   {:doc (:doc (meta #'jq/exception))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica _]
         {:result (fetch-from-zookeeper 
                   peer-config
                   (fn [log] 
                     (let [job-id (get-param request "job-id" :uuid)]
                       (jq/exception log job-id))))})}

   {:uri "/job/task"
    :request-method :get}
   {:doc (:doc (meta #'jq/task-information))
    :query-params-schema
    {"job-id" String
     "task-id" String}
    :f (fn [request peer-config replica _]
         {:result (fetch-from-zookeeper 
                   peer-config
                   (fn [log] 
                     (let [job-id (get-param request "job-id" :uuid)
                           task-id (get-param request "task-id" :keyword)]
                       (jq/task-information log job-id task-id))))})}})

(def serializers
  {"application/edn" pr-str
   "application/string" pr-str
   "application/json" generate-string})

(defn ^{:no-doc true} serializer-name
  [content-type]
  (if (serializers content-type)
    content-type
    default-serializer))

(defn ^{:no-doc true} get-serializer
  [content-type]
  (get serializers 
       content-type
       (get serializers default-serializer)))

(defn handler [replica peer-config state-store-group {:keys [content-type] :as request}]
  (let [serialize (get-serializer content-type)
        f (:f (get endpoints (select-keys request [:request-method :uri])))]
    (try 
     (if-not f
       {:status 404
        :headers {"Content-Type" (serializer-name content-type)}
        :body (serialize {:status :failed :message "Endpoint not found."})}
       (let [{:keys [status result]} (f request peer-config @replica state-store-group)]
         (cond (= "/state" (:uri request))
               {:status (or status 200)
                :headers {"Content-Type" (str (serializer-name content-type) "; charset=utf-8")}
                :body (serialize result)}
               (= "/metrics" (:uri request))
               {:status (or status 200)
                :headers {"Content-Type" (serializer-name content-type)}
                :body result}
               :else
               {:status (or status 200)
                :headers {"Content-Type" (str (serializer-name content-type) "; charset=utf-8")}
                :body (serialize {:status :success
                                  :result result})})))
     (catch Throwable t
       (error t "HTTP peer health query error")
       {:status 500
        :body (pr-str t)}))))

(defn app [replica peer-config state-store-group]
  {:handler (wrap-params (fn [request] (handler replica peer-config state-store-group request)))})

(defrecord QueryServer [state-store-group replica server peer-config]
  component/Lifecycle
  (start [this]
    (let [ip (arg-or-default :onyx.query.server/ip peer-config)
          port (arg-or-default :onyx.query.server/port peer-config)
          replica (atom {})
          server-component (jetty-server {:app (app replica peer-config state-store-group) :host ip :port port})]
      (infof "Starting http query server on %s:%s" ip port)
      (assoc this 
             :replica replica
             :server (component/start server-component))))
  (stop [this]
    (info "Stopping http query server")
    (assoc this 
           :replica nil
           :server (component/stop server)
           :loggin-config nil
           :peer-config nil)))

(defmethod onyx.query/query-server true [peer-config]
  (map->QueryServer {:peer-config peer-config}))

(defmethod clojure.core/print-method QueryServer
  [system ^java.io.Writer writer]
  (.write writer "#<Query Server>"))
