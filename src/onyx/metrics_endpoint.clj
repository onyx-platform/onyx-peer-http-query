(ns onyx.metrics-endpoint
  (:require [clojure.string]
            [clojure.java.jmx :as jmx]))

(defn canonicalize [s]
  (clojure.string/replace s #"[^a-zA-Z0-9:_]" "_"))

(defn remove-jmx-prefix [s]
  (-> s
      (clojure.string/replace #"^name=" "")
      (clojure.string/replace #"^type=" "")))

(defn extract-metric [s]
  (let [ps (partition-all 2 (clojure.string/split s #"[.]"))
        metric (clojure.string/join "_" (last ps))
        tags (reduce into [] (butlast ps))]
    (if (some #{"job-id"} tags)
      {:tags tags :metric metric}
      {:tags [] :metric (clojure.string/replace s #"\." "_")})))

(defn job-metric->metric-str [metric-str attribute]
  (let [{:keys [tags metric]} (extract-metric (remove-jmx-prefix metric-str))
        tag-str (->> tags 
                     (partition 2)
                     (map (fn [[name value]]
                            (format "%s=\"%s\"" (canonicalize name) value)))
                     (clojure.string/join ", ")
                     (format "{%s}"))] 
    (format "%s_%s%s" 
            (canonicalize metric)
            (canonicalize (name attribute)) 
            (if (= "{}" tag-str) "" tag-str))))

(defn blacklisted? [blacklists metric]
  (boolean 
   (some (fn [r] (re-find r metric)) 
         blacklists)))

(defn metrics-endpoint [peer-config]
  (let [builder (java.lang.StringBuilder.)
        blacklists (:onyx.query.server/metrics-blacklist peer-config)
        mbean-selectors (or (:onyx.query.server/metrics-selectors peer-config) ["*:*"])
        selected (atom #{})]
    (doseq [selector mbean-selectors]
      (doseq [mbean (jmx/mbean-names selector)] 
        (try 
         (let [canonical-key (.getCanonicalKeyPropertyListString ^javax.management.ObjectName mbean)]
           ;; deduplicate
           (when-not (@selected canonical-key)
             (swap! selected conj canonical-key)
             (doseq [attribute (jmx/attribute-names mbean)]
               (try 
                (let [value (jmx/read mbean attribute)] 
                  (cond (number? value) 
                        (let [metric (job-metric->metric-str (.getCanonicalKeyPropertyListString ^javax.management.ObjectName mbean) attribute)]
                          (when-not (blacklisted? blacklists metric)
                            (.append builder (format "%s %s" metric value))
                            (.append builder "\n")))
                        (map? value) 
                        (run! (fn [[k v]]
                                (when (number? v)
                                  (let [metric (job-metric->metric-str (.getCanonicalKeyPropertyListString ^javax.management.ObjectName mbean) 
                                                                       (str (name attribute) "_" (name k)))]
                                    (when-not (blacklisted? blacklists metric)
                                      (.append builder (format "%s %s" metric v))
                                      (.append builder "\n")))))
                              value)))
                ;; Safe to swallow, jobs and beans are ephemeral
                (catch javax.management.RuntimeMBeanException _)))))
         ;; Safe to swallow, jobs and beans are ephemeral
         (catch javax.management.InstanceNotFoundException _))))
    (str builder)))
