(ns onyx.http-query
  (:require [onyx.static.default-vals :refer [arg-or-default]]
            [ring.component.jetty :refer [jetty-server]]
            [ring.middleware.params :refer [wrap-params]]
            [com.stuartsierra.component :as component]
            [cheshire.core :refer [generate-string]]
            [onyx.query]
            [onyx.peer-query.job-query :as jq]
            [onyx.system :as system]
            [onyx.peer-query.aeron]
            [clojure.java.jmx :as jmx]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [info error infof]]))

(def default-serializer "application/edn")

(def parse-uuid #(java.util.UUID/fromString %))

(defn parse-keyword [kw-str]
  (keyword (clojure.string/replace kw-str #"^:" "")))

(def parsers 
  {:keyword parse-keyword
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

(def extractions
  {:job-id #"(?:[.]|=)job[.]([^_]+)"
   :task #"(?:[.]|=)task[.]([^_]+)"
   :peer-id #"(?:[.]|=)peer-id[.]([^_]+)"})

(defn remove-tags [s]
  (clojure.string/replace (reduce (fn [s [k v]]
                                    (clojure.string/replace s v ""))
                                  s
                                  extractions) 
                          #"_$"
                          ""))

(defn canonicalize [s]
  (-> s 
      (clojure.string/replace #"[ ]" "_")
      (clojure.string/replace #"[.]" "_")
      (clojure.string/replace #"[-]" "_")))

(defn remove-jmx-prefix [s]
  (clojure.string/replace s #"^name=" ""))


(defn extract-metric [s]
  (loop [[p & ps] (clojure.string/split s #"[.]")
         tags []
         metric ""]
    (cond (nil? p)
          {:tags tags :metric metric}

          (#{"task" "job" "peer-id"} p) 
          (recur (rest ps)
                 (conj tags p (first ps))
                 metric)
          :else
          (recur ps
                 tags
                 (if (empty? metric)
                   p
                   (str metric "_" p))))))

(defn job-metric->metric-str [metric-str attribute value]
  (let [{:keys [tags metric]} (extract-metric (remove-jmx-prefix metric-str))
        tag-str (->> tags 
                     (partition 2)
                     (map (fn [[name value]]
                            (format "%s=%s" name value)))
                     (clojure.string/join ", ")
                     (format "{%s}"))] 
    (format "%s_%s%s %s" 
            (canonicalize metric)
            (name attribute) 
            (if (= "{}" tag-str) "" tag-str)
            value)))

(defn metrics-endpoint []
  (let [builder (java.lang.StringBuilder.)] 
    (doseq [mbean (jmx/mbean-names "metrics:*")] 
      (doseq [attribute (jmx/attribute-names mbean)]
        (try 
         (let [value (jmx/read mbean attribute)] 
           (when-not (string? value) 
             (.append builder (job-metric->metric-str (.getCanonicalKeyPropertyListString mbean) 
                                                      attribute 
                                                      value))
             (.append builder "\n")))
         ;; Safe to swallow
         (catch javax.management.RuntimeMBeanException _))))
    (str builder)))

(def endpoints
  {{:uri "/network/media-driver"
    :request-method :get}
   {:doc "Returns a map describing the media driver status."
    :f (fn [request _ _] (onyx.peer-query.aeron/media-driver-health))}

   {:uri "/network/media-driver/active"
    :request-method :get}
   {:doc "Returns a boolean for whether the media driver is healthy and heartbeating."
    :f (fn [request _ _] (:active (onyx.peer-query.aeron/media-driver-health)))}
   
   {:uri "/metrics"
    :request-method :get}
   {:doc "Returns metrics for prometheus"
    :f (fn [request _ _] (metrics-endpoint))}
   
   {:uri "/replica"
    :request-method :get}
   {:doc "Returns a snapshot of the replica"
    :f (fn [request _ replica] replica)}

   {:uri "/replica/peers"
    :request-method :get}
   {:doc "Lists all the peer ids"
    :f (fn [request _ replica]
         (:peers replica))}

   {:uri "/replica/jobs"
    :request-method :get}
   {:doc "Lists all non-killed, non-completed job ids."
    :f (fn [request _ replica]
         (:jobs replica))}

   {:uri "/replica/killed-jobs"
    :request-method :get}
   {:doc "Lists all the job ids that have been killed."
    :f (fn [request _ replica]
         (:killed-jobs replica))}

   {:uri "/replica/completed-jobs"
    :request-method :get}
   {:doc "Lists all the job ids that have been completed."
    :f (fn [request _ replica]
         (:completed-jobs replica))}

   {:uri "/replica/tasks"
    :request-method :get}
   {:doc "Given a job id, returns all the task ids for this job."
    :query-params-schema {"job-id" String}
    :f (fn [request _ replica]
         (let [job-id (get-param request "job-id" :uuid)]
           (get-in replicate [:tasks job-id])))}

   {:uri "/replica/job-allocations"
    :request-method :get}
   {:doc "Returns a map of job id -> task id -> peer ids, denoting which peers are assigned to which tasks."
    :f (fn [request _ replica]
         (:allocations replica))}

   {:uri "/replica/task-allocations"
    :request-method :get}
   {:doc "Given a job id, returns a map of task id -> peer ids, denoting which peers are assigned to which tasks for this job only."
    :f (fn [request _ replica]
         (let [job-id (get-param request "job-id" :uuid)]
           (get-in replicate [:allocations job-id])))}

   {:uri "/replica/peer-site"
    :request-method :get}
   {:doc "Given a peer id, returns the Aeron hostname and port that this peer advertises to the rest of the cluster."
    :query-params-schema {"peer-id" String}
    :f (fn [request _ replica]
         (let [peer-id (get-param request "peer-id" :uuid)]
           (get-in replica [:peer-sites peer-id])))}

   {:uri "/replica/peer-state"
    :request-method :get}
   {:doc "Given a peer id, returns its current execution state (e.g. :idle, :active, etc)."
    :query-params-schema {"peer-id" String}
    :f (fn [request _ replica]
         (let [peer-id (get-param request "peer-id" :uuid)]
           (get-in replica [:peer-state peer-id])))}

   {:uri "/replica/job-scheduler"
    :request-method :get}
   {:doc "Returns the job scheduler for this tenancy of the cluster."
    :f (fn [request _ replica]
         (:job-scheduler replica))}

   {:uri "/replica/task-scheduler"
    :request-method :get}
   {:doc "Given a job id, returns the task scheduler for this job."
    :query-params-schema
    {"job-id" String}
    :f (fn [request _ replica]
         (let [job-id (get-param request "job-id" :uuid)]
           (get-in replica [:task-schedulers job-id])))}


   {:uri "/job/workflow"
    :request-method :get}
   {:doc (:doc (meta #'jq/workflow))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica]
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
    :f (fn [request peer-config replica]
         (fetch-from-zookeeper 
          peer-config
          (fn [log] 
            (let [job-id (get-param request "job-id" :uuid)]
              (jq/catalog log job-id)))))}

   {:uri "/job/flow-conditions"
    :request-method :get}
   {:doc (:doc (meta #'jq/flow-conditions))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica]
         (fetch-from-zookeeper 
          peer-config
          (fn [log] 
            (let [job-id (get-param request "job-id" :uuid)]
              (jq/flow-conditions log job-id)))))}

   {:uri "/job/lifecycles"
    :request-method :get}
   {:doc (:doc (meta #'jq/lifecycles))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica]
         (fetch-from-zookeeper 
          peer-config
          (fn [log] 
            (let [job-id (get-param request "job-id" :uuid)]
              (jq/lifecycles log job-id)))))}

   {:uri "/job/windows"
    :request-method :get}
   {:doc (:doc (meta #'jq/windows))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica]
         (fetch-from-zookeeper 
          peer-config
          (fn [log] 
            (let [job-id (get-param request "job-id" :uuid)]
              (jq/windows log job-id)))))}

   {:uri "/job/triggers"
    :request-method :get}
   {:doc (:doc (meta #'jq/triggers))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica]
         (fetch-from-zookeeper 
          peer-config
          (fn [log] 
            (let [job-id (get-param request "job-id" :uuid)]
              (jq/triggers log job-id)))))}

   {:uri "/job/exception"
    :request-method :get}
   {:doc (:doc (meta #'jq/exception))
    :query-params-schema
    {"job-id" String}
    :f (fn [request peer-config replica]
         (fetch-from-zookeeper 
          peer-config
          (fn [log] 
            (let [job-id (get-param request "job-id" :uuid)]
              (jq/exception log job-id)))))}

   {:uri "/job/task"
    :request-method :get}
   {:doc (:doc (meta #'jq/task-information))
    :query-params-schema
    {"job-id" String
     "task-id" String}
    :f (fn [request peer-config replica]
         (fetch-from-zookeeper 
          peer-config
          (fn [log] 
            (let [job-id (get-param request "job-id" :uuid)
                  task-id (get-param request "task-id" :keyword)]
              (jq/task-information log job-id task-id)))))}})

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

(defn handler [replica peer-config {:keys [content-type] :as request}]
  (let [serialize (get-serializer content-type)
        f (:f (get endpoints (select-keys request [:request-method :uri])))]
    (try 
     (if-not f
       {:status 404
        :headers {"Content-Type" (serializer-name content-type)}
        :body (serialize {:status :failed :message "Endpoint not found."})}
       (let [result (f request peer-config @replica)]
         {:status 200
          :headers {"Content-Type" (serializer-name content-type)}
          :body (if (= "/metrics" (:uri request)) 
                  result
                  (serialize {:status :success
                              :result result}))}))
     (catch Throwable t
       (error t "HTTP peer health query error")
       {:status 500
        :body (pr-str t)}))))

(defn app [replica peer-config]
  {:handler (wrap-params (fn [request] (handler replica peer-config request)))})

(defrecord QueryServer [replica server peer-config]
  component/Lifecycle
  (start [this]
    (let [ip (arg-or-default :onyx.query.server/ip peer-config)
          port (arg-or-default :onyx.query.server/port peer-config)
          replica (atom {})
          server-component (jetty-server {:app (app replica peer-config) :host ip :port port})]
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
