(defproject org.onyxplatform/onyx-peer-http-query "0.10.0.0-alpha1"
  :description "An Onyx health and query HTTP server"
  :url "https://github.com/onyx-platform/onyx-peer-http-query"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [ring/ring-core "1.5.1"]
                 [org.clojure/java.jmx "0.3.3"]
                 [ring-jetty-component "0.3.1"]
                 [cheshire "5.7.0"]]
  :repositories {"snapshots" {:url "https://clojars.org/repo"
                              :username :env
                              :password :env
                              :sign-releases false}
                 "releases" {:url "https://clojars.org/repo"
                             :username :env
                             :password :env
                             :sign-releases false}}
  :profiles {:dev {:dependencies [[clj-http "3.4.1"]
                                  [org.onyxplatform/onyx-metrics "0.10.0.0-alpha1"]
                                  [org.onyxplatform/onyx "0.9.16-20170208_065307-g0aa5941"]]
                   :plugins [[lein-set-version "0.4.1"]
                             [lein-update-dependency "0.1.2"]
                             [lein-pprint "1.1.1"]]}})
