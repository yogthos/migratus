(defproject migratus "0.7.1"
  :description "MIGRATE ALL THE THINGS!"
  :url "http://github.com/pjstadig/migratus"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :aliases {"test!" ["do" "clean," "test"]}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/java.classpath "0.2.2"]
                 [org.clojure/java.jdbc "0.3.7"]
                 [org.clojure/tools.logging "0.3.1"]
                 [robert/bruce "0.7.1"]]
  :profiles {:dev {:dependencies [[jar-migrations "1.0.0"]
                                  [log4j "1.2.17"]
                                  [mysql/mysql-connector-java "5.1.35"]]}})
