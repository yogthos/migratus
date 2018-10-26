(defproject migratus "1.1.6"
  :description "MIGRATE ALL THE THINGS!"
  :url "http://github.com/yogthos/migratus"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :aliases {"test!" ["do" "clean," "test"]}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [org.clojure/tools.logging "0.4.1"]]
  :profiles {:dev {:dependencies [[jar-migrations "1.0.0"]
                                  [ch.qos.logback/logback-classic "1.2.3"]
                                  [com.h2database/h2 "1.4.197"]]}})
