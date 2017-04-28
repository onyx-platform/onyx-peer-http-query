(ns onyx.peer-query.aeron
  (:import [io.aeron CommonContext]
           [java.util.function Consumer]))

(defn media-driver-health []
  (let [common-context (.conclude (CommonContext.))
        log-output (atom [])]
    (try 
     (let [driver-timeout-ms (.driverTimeoutMs common-context)
           active? (.isDriverActive common-context 
                                    driver-timeout-ms 
                                    (reify Consumer
                                      (accept [this log]
                                        (swap! log-output conj log))))]
       {:active active?
        :aeron-dir (.aeronDirectoryName common-context)
        :driver-timeout-ms driver-timeout-ms
        :log (clojure.string/join "\n" @log-output)})
     (finally
      (.close common-context)))))
