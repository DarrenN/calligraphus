(ns calligraphus.core
  (:require [clojure.core.async :as async :refer (<!! >! chan go)]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [clj-yaml.core :as yaml]
            [cheshire.core :refer :all]))

; Meetup.com API key
(def api-key "512d751a3e41262c3e387533222581f")

(def chapter-results (atom {}))

; Load chapter data
(def chapter-yaml (io/file "resources/yaml/chapters.yml"))
(def chapters (yaml/parse-string (slurp chapter-yaml)))

; List of url formatted chapter names - used to hit API for group info
(def chapter-urls (map :meetup_url chapters))

; Groups endpoints
(def chapter-uri-template "http://api.meetup.com/2/groups?radius=25.0&order=id&group_urlname=%s&desc=false&offset=0&photo-host=public&format=json&page=50&key=%s")

; Pluck a map from a vector based on predicate
(defn pluck [pred vec]
  (first (filter pred vec)))

(defn get-id [id coll]
  (pluck #(= (:id %) id) coll))

; Get a url and drop the result into a channel
(defn async-get [url result]
  (http/get url #(go (>! result %))))

; Seq through the chapter urls and get their representations from API
(def group-data
  (let [c (chan)]
    ;; get em
    (doseq [group chapter-urls]
      (async-get (format chapter-uri-template group api-key) c))
    ;; gather em
    (doseq [_ chapter-urls]
      (let [r (<!! c)
            body (walk/keywordize-keys (parse-string (:body r)))
            results (first (:results body))]
        (swap! chapter-results assoc (:id results) results)))
    @chapter-results))
