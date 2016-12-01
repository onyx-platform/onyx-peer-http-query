(ns onyx.peer-query.aeron
  (:import [io.aeron CommonContext]
           [java.util.function Consumer]))

(defn media-driver-health []
  (let [common-context (CommonContext.)
        log-output (atom [])]
    (try 
     (let [driver-timout-ms (.driverTimeoutMs common-context)
           active? (.isDriverActive common-context 
                                    driver-timout-ms 
                                    (reify Consumer
                                      (accept [this log]
                                        (swap! log-output conj log))))]
       {:active active?
        :driver-timeout-ms driver-timout-ms
        :log (clojure.string/join "\n" @log-output)})
     (finally
      (.close common-context)))))
