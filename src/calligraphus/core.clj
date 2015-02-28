(ns calligraphus.core
  (:require [calligraphus.meetup :as meetup]
            [calligraphus.facebook :as facebook]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [clojure.pprint :as pretty]
            [clojure.tools.logging :as log]
            [clj-yaml.core :as yaml]
            [cheshire.core :refer :all]
            [cemerick.url :refer (url url-encode)]))

(defn match-api
  "Match url host against string to determine which API namespace to call"
  [u]
  (let [host (:host (url u))]
    (cond
      (re-matches #".*facebook.*" host) {:api "facebook", :url u}
      :else {:api "", :url u})))

(defn process-url
  "Inspect urls and return a map denoting which API namespace it should use"
  [url]
  (let [is-url (re-matches #"^http.*" url)]
    (if is-url
      (match-api url)
      {:api "meetup", :url url})))

(defn build-map
  "Inspect collection for API namespace information. If that namespace exists
  we pass the collection to its get-chapters function or pass the map back."
  [m chapters]
  (let [api (:api (first chapters))]
    (if (find-ns (symbol (str "calligraphus." api)))
      (let [f (ns-resolve (symbol (str "calligraphus." api)) 'get-chapters)]
        (conj m (f chapters)))
      m)))

(defn transcribe
  "Take a yaml file and harvest various API data, saving a new yaml file"
  [file-in file-out]
  (let [fhandle (io/file file-in)
        chapters (yaml/parse-string (slurp fhandle))
        urls (remove nil? (map :meetup_url chapters))
        sorted (partition-by :api (sort-by :api (map process-url urls)))
        chapter-map (reduce build-map {} sorted)]
    (spit file-out (yaml/generate-string chapter-map))))

;; (transcribe "chapters.yml" "foo.yml")
