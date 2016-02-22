(defproject migratus "0.8.11"
  :description "MIGRATE ALL THE THINGS!"
  :url "http://github.com/yogthos/migratus"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :aliases {"test!" ["do" "clean," "test"]}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/java.classpath "0.2.3"]
                 [org.clojure/java.jdbc "0.4.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [camel-snake-kebab "0.3.2"]]
  :profiles {:dev {:dependencies [[jar-migrations "1.0.0"]
                                  [log4j "1.2.17"]
                                  [com.h2database/h2 "1.4.190"]]}})
