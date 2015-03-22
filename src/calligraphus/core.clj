(ns calligraphus.core
  (:gen-class)
  (:require [calligraphus.meetup :as meetup]
            [calligraphus.facebook :as facebook]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [clojure.pprint :as pretty]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :refer [parse-opts]]
            [clj-yaml.core :as yaml]
            [cheshire.core :refer :all]
            [cemerick.url :refer (url url-encode)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; private

;; api-dispatch determines which API endpoint namespace to use in gathering
;; chapter data
(defmulti api-dispatch (fn [chapters]
                (:api (first chapters))))

(defmethod api-dispatch "meetup"
  [chapters]
  (meetup/get-chapters chapters))

(defmethod api-dispatch "facebook"
  [chapters]
  (facebook/get-chapters chapters))

(defn match-api
  "Tag url with an API namespace, determined by inspecting the url"
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
  (conj m (api-dispatch chapters)))

;;
;; CLI support
;;

(def cli-options
  [["-i" "--in PATH" "Path to input yaml"]
   ["-o" "--out PATH" "Path to output yaml"]
   ["-h" "--help"]])

(defn usage [options-summary]
  "Pretty print the CLI options"
  (->> ["Calligraphus"
        "------------"
        ""
        "Options:"
        options-summary
        ""]
       (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occured while attempting to transcribe:\n\n"
       (str/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public

(defn transcribe
  "Take a yaml file and harvest various API data, saving a new yaml file"
  [file-in file-out]
  (let [fhandle (io/file file-in)
        chapters (yaml/parse-string (slurp fhandle))
        urls (remove nil? (map :meetup_url chapters))
        sorted (partition-by :api (sort-by :api (map process-url urls)))
        chapter-map (reduce build-map {} sorted)]
    (spit file-out (yaml/generate-string chapter-map))))

(defn -main
  "CLI interface from uberjar"
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      (not= (count options) 2) (exit 2 (usage summary))
      errors (exit 1 (error-msg errors)))
    (transcribe (:in options) (:out options))))
