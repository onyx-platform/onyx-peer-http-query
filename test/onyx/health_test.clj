(ns onyx.health-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is testing]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config with-test-env add-test-env-peers!]]
            [onyx.static.uuid :refer [random-uuid]]
            [com.stuartsierra.component :as component]
            [onyx.http-query]
            [clj-http.client :as client]
            [onyx.api]))

(def n-messages 100)

(def in-chan (atom nil))
(def in-buffer (atom nil))

(def out-chan (atom nil))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer in-buffer
   :core.async/chan @in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan @out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(defn ignore [& rst])

(deftest health-test
  (let [id (random-uuid)
        env-config {:zookeeper/address "127.0.0.1:2188"
                    :zookeeper/server? true
                    :zookeeper.server/port 2188
                    :onyx.bookkeeper/server? true
                    :onyx.bookkeeper/delete-server-data? true
                    :onyx.bookkeeper/local-quorum? true
                    :onyx.bookkeeper/local-quorum-ports [3196 3197 3198]
                    :onyx/tenancy-id id} 
        peer-config {:zookeeper/address "127.0.0.1:2188"
                     :onyx.peer/job-scheduler :onyx.job-scheduler/greedy
                     :onyx.peer/zookeeper-timeout 60000
                     :onyx.messaging.aeron/embedded-driver? true
                     :onyx.messaging/allow-short-circuit? false
                     :onyx.messaging/impl :aeron
                     :onyx.messaging/peer-port 40199
                     :onyx.messaging/bind-addr "localhost"
                     :onyx.query.server/metrics-selectors ["com.amazonaws.management:*" "*:*"]
                     :onyx/tenancy-id id
                     :onyx.query/server? true
                     :onyx.query.server/ip "127.0.0.1"
                     :onyx.query.server/port 8091}]
    (with-test-env [test-env [3 env-config peer-config]]
      (let [batch-size 20
	    catalog [{:onyx/name :in
		      :onyx/plugin :onyx.plugin.core-async/input
		      :onyx/type :input
		      :onyx/medium :core.async
		      :onyx/batch-size batch-size
		      :onyx/max-peers 1
		      :onyx/doc "Reads segments from a core.async channel"}

		     {:onyx/name :my/inc
		      :onyx/fn :onyx.health-test/my-inc
		      :onyx/type :function
                      :onyx/group-by-key :what
                      :onyx/flux-policy :recover
                      :onyx/n-peers 1
		      :onyx/batch-size batch-size}

		     {:onyx/name :out
		      :onyx/plugin :onyx.plugin.core-async/output
		      :onyx/type :output
		      :onyx/medium :core.async
		      :onyx/batch-size batch-size
		      :onyx/max-peers 1
		      :onyx/doc "Writes segments to a core.async channel"}]
            ;; randomize window-id type for test
            window-id (rand-nth [:my/window-id :my-window-id (java.util.UUID/randomUUID)])
	    windows
	    [{:window/id window-id
	      :window/task :my/inc 
              :window/type :fixed 
              :window/window-key :event-time 
              :window/range [5 :minutes]
	      :window/aggregation :onyx.windowing.aggregation/count}]
	    triggers
	    [{:trigger/window-id window-id
	      :trigger/id :sync
	      :trigger/refinement :onyx.refinements/accumulating
	      :trigger/fire-all-extents? true
	      :trigger/on :onyx.triggers/segment
	      :trigger/threshold [15 :elements]
	      :trigger/sync ::ignore}]

	    workflow [[:in :my/inc] [:my/inc :out]]
	    lifecycles [{:lifecycle/task :in
			 :lifecycle/calls :onyx.health-test/in-calls}
			{:lifecycle/task :out
			 :lifecycle/calls :onyx.health-test/out-calls}]
	    _ (reset! in-chan (chan (inc n-messages)))
	    _ (reset! in-buffer {})
	    _ (reset! out-chan (chan (sliding-buffer (inc n-messages))))
	    _ (doseq [n (range n-messages)]
		(>!! @in-chan {:n n :event-time (long (rand-int 10000000))}))
	    job-id (:job-id (onyx.api/submit-job peer-config
						 {:catalog catalog
						  :workflow workflow
						  :lifecycles lifecycles
                                                  :windows windows
                                                  :triggers triggers
						  :task-scheduler :onyx.task-scheduler/balanced
						  :metadata {:job-name :click-stream}}))
	    _ (Thread/sleep 2000)
	    peers (:result (clojure.edn/read-string (:body (client/get "http://127.0.0.1:8091/replica/peers"))))]
        (mapv (fn [[{:keys [uri]} {:keys [query-params-schema]}]]
                (println "uri:" uri)
                (if (= "/metrics" uri)
                  (do
                  (println (:body (client/get (str "http://127.0.0.1:8091" uri) 
                                                  {:query-params {}})))
                   (is (re-find #"replica_version" 
                               (:body (client/get (str "http://127.0.0.1:8091" uri) 
                                                  {:query-params {}})))))
                  (is (= :success 
                         (time (:status 
                          (doto 
                            (clojure.edn/read-string 
                             (:body (client/get (str "http://127.0.0.1:8091" uri) 
                                                {:query-params {"task-id" "out"
                                                                "threshold" 10000
                                                                "replica-version" 4
                                                                "slot-id" 0
                                                                "task" :my/inc
                                                                "window" window-id
                                                                "peer-id" (first peers)
                                                                "job-id" (str job-id)}})))
                            println))))))) 
              onyx.http-query/endpoints)
        (let [_ (close! @in-chan)
              _ (onyx.test-helper/feedback-exception! peer-config job-id)
              results (take-segments! @out-chan 500)]
          (is (= [job-id] (:result (clojure.edn/read-string (:body (client/get "http://127.0.0.1:8091/replica/completed-jobs"))))))
          (is (= (map inc (range n-messages)) (sort (map :n results)))))))))
