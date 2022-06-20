(defproject migratus "1.3.7"
  :description "MIGRATE ALL THE THINGS!"
  :url "http://github.com/yogthos/migratus"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :aliases {"test!" ["do" "clean," "test"]}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/java.jdbc "0.7.11"]
                 [com.taoensso/timbre "5.2.1"]]
  :profiles {:dev {:dependencies [[jar-migrations "1.0.0"]
                                  [com.h2database/h2 "1.4.200"]
                                  [hikari-cp "2.13.0"]]}})
