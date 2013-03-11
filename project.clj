(defproject migratus "0.6.0"
  :description "MIGRATE ALL THE THINGS!"
  :url "http://github.com/pjstadig/migratus"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :aliases {"test" "conjecture"
            "test!" ["do" "clean," "test"]}
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/java.classpath "0.1.0"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.clojure/tools.logging "0.2.3"]
                 [robert/bruce "0.7.1"]]
  :plugins [[lein-conjecture "0.2.0-SNAPSHOT"]]
  :dev-dependencies [[jar-migrations "1.0.0"]
                     [log4j "1.2.16"]
                     [mysql/mysql-connector-java "5.1.18"]
                     [conjecture "0.3.0-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[jar-migrations "1.0.0"]
                                  [log4j "1.2.16"]
                                  [mysql/mysql-connector-java "5.1.18"]
                                  [conjecture "0.3.0-SNAPSHOT"]]}})
