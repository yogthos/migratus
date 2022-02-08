(ns release.core
  (:require ["fs" :as fs]
            [clojure.edn :as edn]            
            [clojure.string :refer [replace-first split]]))

(def exec (.-execSync (js/require "child_process")))

(defn bump-version [v]
  (let [[x y z] (map js/parseInt (split v #"\."))]
       (cond
         (= 9 y z) (str (inc x) ".0.0")
         (= 9 z) (str x "." (inc y) ".0")
         :else (str x "." y "." (inc z)))))

(defn write-version [project version]
  (fs/writeFileSync
      "project.clj"
      (replace-first project #"\d+\.\d+\.\d+" version)))

(defn increment-version []
  (let [project (->> "project.clj" (fs/readFileSync) (str))
        version (->> project (edn/read-string) (vec) (drop 2) first bump-version)]
    (write-version project version)
    version))

(defn run [command]
  ( println (str (exec command))))

(let [version (increment-version)]
  (println "releasing version:" version)
  (run (str "git commit -a -m \"release version " version "\""))
  (run "git push")
  (run (str "git tag -a v" version " -m \"release " version "\"" ))
  (run "git push --tags"))
