(ns onyx.peer-query.job-query
  (:require [onyx.log.commands.common :as common]
            [onyx.log.zookeeper :as zk]
            [onyx.static.planning :refer [find-task]]
            [onyx.peer-query.replica-query :as rq]
            [onyx.extensions :as extensions])
  (:import org.apache.zookeeper.KeeperException$NoNodeException))

(defn ^{:no-doc true} get-log [log]
  (:log (:env log)))

(defn ^{:no-doc true} find-in-seq [coll k v]
  (first (filter #(= v (k %)) coll)))

(defn workflow
  "Given a job id, returns workflow for this job."
  [log job-id]
  (extensions/read-chunk log :workflow job-id))

(defn catalog
  "Given a job id, returns catalog for this job."
  [log job-id]
  (extensions/read-chunk log :catalog job-id))

(defn job-metadata
  "Given a job id, returns job-metadata for this job."
  [log job-id]
  (or (extensions/read-chunk log :job-metadata job-id)))

(defn flow-conditions
  "Given a job id, returns flow conditions for this job."
  [log job-id]
  (or (extensions/read-chunk log :flow-conditions job-id) []))

(defn lifecycles
  "Given a job id, returns lifecycles for this job."
  [log job-id]
  (or (extensions/read-chunk log :lifecycles job-id) []))

(defn windows
  "Given a job id, returns windows for this job."
  [log job-id]
  (or (extensions/read-chunk log :windows job-id) []))

(defn triggers
  "Given a job id, returns triggers for this job."
  [log job-id]
  (or (extensions/read-chunk log :triggers job-id) []))

(defn exception
  "Given a job id, return the exception for this job, if any"
  [log job-id]
  (try (extensions/read-chunk log :exception job-id)
       ;; Workaround for the fact that we don't handle this upstream
       (catch org.apache.zookeeper.KeeperException$NoNodeException nne
         (println "Exception not found for job" job-id))))

(defn job-information 
  "Given a job id and task id, returns job data for this task."
  [log replica job-id]
  {:workflow (workflow log job-id)
   :catalog (catalog log job-id)
   :flow-conditions (flow-conditions log job-id)
   :lifecycles (lifecycles log job-id)
   :windows (windows log job-id)
   :triggers (triggers log job-id)
   :metadata (job-metadata log job-id)
   :task-scheduler (rq/task-scheduler replica job-id)})

(defn task-name 
  "Given a task id, returns the task name" 
  [log task-id]
  (:name (extensions/read-chunk log :task task-id)))

(defn task-information
  "Given a job id and task id, returns catalog entry for this task."
  [log job-id task-id]
  (let [task-name (:name (extensions/read-chunk log :task job-id task-id))]
    (find-task (extensions/read-chunk log :catalog job-id) task-name)))
