(defproject migratus "1.3.8"
  :description "MIGRATE ALL THE THINGS!"
  :url "http://github.com/yogthos/migratus"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :aliases {"test!" ["do" "clean," "test"]}
  :dependencies [[com.github.seancorfield/next.jdbc "1.2.780"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.logging "1.1.0"]]
  :profiles {:dev {:dependencies [[jar-migrations "1.0.0"]
                                  [ch.qos.logback/logback-classic "1.2.3"]
                                  [clj-test-containers/clj-test-containers "0.7.1"]
                                  [com.h2database/h2 "2.1.214"]
                                  [hikari-cp/hikari-cp "2.13.0"]
                                  [org.clojure/tools.trace "0.7.11"]
                                  [org.postgresql/postgresql "42.2.5"]]}})
