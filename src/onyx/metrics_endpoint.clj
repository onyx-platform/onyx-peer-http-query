(ns onyx.metrics-endpoint
  (:require [clojure.string]
            [clojure.java.jmx :as jmx]))

(def extractions
  {:job-id #"(?:[.]|=)job[.]([^_]+)"
   :task #"(?:[.]|=)task[.]([^_]+)"
   :slot-id #"(?:[.]|=)slot-id[.]([^_]+)"
   :peer-id #"(?:[.]|=)peer-id[.]([^_]+)"})

(defn remove-tags [s]
  (clojure.string/replace (reduce (fn [s [k v]]
                                    (clojure.string/replace s v ""))
                                  s
                                  extractions) 
                          #"_$"
                          ""))

(defn canonicalize [s]
  (clojure.string/replace s #"[^a-zA-Z0-9:_]" "_"))

(defn remove-jmx-prefix [s]
  (-> s
      (clojure.string/replace #"^name=" "")
      (clojure.string/replace #"^type=" "")))

(defn extract-metric [s]
  (loop [[p & ps] (clojure.string/split s #"[.]")
         tags []
         metric ""]
    (cond (nil? p)
          {:tags tags :metric metric}

          (#{"task" "job" "peer-id" "slot-id"} p) 
          (recur (rest ps)
                 (conj tags p (first ps))
                 metric)
          :else
          (recur ps
                 tags
                 (if (empty? metric)
                   p
                   (str metric "_" p))))))

(defn job-metric->metric-str [metric-str attribute]
  (let [{:keys [tags metric]} (extract-metric (remove-jmx-prefix metric-str))
        tag-str (->> tags 
                     (partition 2)
                     (map (fn [[name value]]
                            (format "%s=\"%s\"" (canonicalize name) (canonicalize value))))
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
        (let [canonical-key (.getCanonicalKeyPropertyListString mbean)]
          ;; deduplicate
          (when-not (@selected canonical-key)
            (swap! selected conj canonical-key)
            (doseq [attribute (jmx/attribute-names mbean)]
              (try 
               (let [value (jmx/read mbean attribute)] 
                 (cond (number? value) 
                       (let [metric (job-metric->metric-str (.getCanonicalKeyPropertyListString mbean) attribute)]
                         (when-not (blacklisted? blacklists metric)
                           (.append builder (format "%s %s" metric value))
                           (.append builder "\n")))
                       (map? value) 
                       (run! (fn [[k v]]
                               (when (number? v)
                                 (let [metric (job-metric->metric-str (.getCanonicalKeyPropertyListString mbean) 
                                                                      (str (name attribute) "_" (name k)))]
                                   (when-not (blacklisted? blacklists metric)
                                     (.append builder (format "%s %s" metric v))
                                     (.append builder "\n")))))
                             value)))
               ;; Safe to swallow
               (catch javax.management.RuntimeMBeanException _)))))))
    (str builder)))
