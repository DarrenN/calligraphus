(ns calligraphus.core
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [clj-yaml.core :as yaml]
            [cheshire.core :refer :all]))

; Meetup.com API key
(def api-key "512d751a3e41262c3e387533222581f")

(def chapter-results (atom []))

; Load chapter data
(def chapter-yaml (io/file "resources/yaml/chapters.yml"))
(def chapters (yaml/parse-string (slurp chapter-yaml)))

; List of url formatted chapter names - used to hit API for group info
(def chapter-urls (map :meetup_url chapters))

; Groups endpoints
(def chapter-uri-template "http://api.meetup.com/2/groups?radius=25.0&order=id&group_urlname=%s&desc=false&offset=0&photo-host=public&format=json&page=50&key=%s")

(defn clean-group [group]
  (let [body (walk/keywordize-keys (parse-string (:body group)))]
    (first (:results body))))


; Get a url and drop the result into a channel
(defn async-get [url result]
  (http/get url #(async/go (async/>! result %))))

;; group-chan (async/chan 1 get-group)
;; events-chan (async/chan 1 get-events)
;; photos-chan (async/chan 1 get-photos)
;; results-chan (async/reduce #(conj %1 %2) [] photos-chan)
;;
;; (async/onto-chan group-chan chapters) send chapters one by one into channel
;;
;;

(def group-chan (async/chan))

(defn get-group [group]
  (async-get (format chapter-uri-template group api-key) group-chan))

;(def results-chan (async/reduce #(conj %1 %2) [] group-chan))

;(def hydrated (async/<!! results-chan))

;(async/onto-chan group-chan chapter-urls)
(doseq [group chapter-urls]
  (get-group group)
  (let [r (async/<!! group-chan)]
    (swap! chapter-results conj (clean-group r))))

;; ; Seq through the chapter urls and get their representations from API
;; (def group-data
;;   (let [c (async/chan)]
;;     ;; get em
;;     (doseq [group chapter-urls]
;;       (async-get (format chapter-uri-template group api-key) c))
;;     ;; gather em
;;     (doseq [_ chapter-urls]
;;       (let [r (async/<!! c)
;;             body (walk/keywordize-keys (parse-string (:body r)))
;;             results (first (:results body))]
;;         (swap! chapter-results assoc (:id results) results)))
;;     @chapter-results))
