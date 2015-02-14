(defproject calligraphus "0.1.0-SNAPSHOT"
  :description "Meetup.com API aggregator for Papers We Love"
  :url "http://paperswelove.org"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-alpha5"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.3.1"]
                 [http-kit "2.1.19"]
                 [circleci/clj-yaml "0.5.3"]
                 [cheshire "5.4.0"]])
