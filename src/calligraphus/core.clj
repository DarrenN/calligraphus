(ns calligraphus.core
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [clojure.pprint :as pretty]
            [org.httpkit.client :as http]
            [clj-yaml.core :as yaml]
            [cheshire.core :refer :all]))

; Meetup.com API key
(def api-key "4113124f79194d5f676f152d51377b54")

(def chapter-results (atom []))
(def result-limit (atom 0))

; Load chapter data
(def chapter-yaml (io/file "resources/yaml/chapters.yml"))
(def chapters (yaml/parse-string (slurp chapter-yaml)))

; List of url formatted chapter names - used to hit API for group info
(def chapter-urls (remove nil? (map :meetup_url chapters)))

; Groups endpoints
(def group-uri-template "http://api.meetup.com/2/groups?radius=25.0&order=id&group_urlname=%s&desc=false&offset=0&photo-host=public&format=json&page=50&key=%s")

(def event-uri-template "https://api.meetup.com/2/events?&sign=true&photo-host=public&status=upcoming,past&group_id=%s&fields=photo_album_id&page=50&key=%s")

(def photo-uri-template "https://api.meetup.com/2/photos?&sign=true&photo-host=public&photo_album_id=%s&page=20&key=%s")

(defn clean-group [resp]
  (let [body (walk/keywordize-keys (parse-string (:body resp)))]
    (first (:results body))))

(defn clean-events [resp]
  (let [body (walk/keywordize-keys (parse-string (:body resp)))]
    (:results body)))

; Get a url and drop the result into a channel
(defn async-get [prev url c]
  (http/get url #(async/go
                   (async/<! (async/timeout 2000)) ; throttle the reqs
                   (async/>! c [prev %]))))

(defn get-group [group c]
  (async-get " " (format group-uri-template group api-key) c))

(defn get-event [payload c]
  (let [[_ group] payload
        cgroup (clean-group group)
        group_url (:id cgroup)]
    (async-get cgroup (format event-uri-template group_url api-key) c)))

;; Take a channel that receives [group events] and loop through the events
;; making photo album requests, and combining those results with the event
;; return the group
(defn get-photo-map
  [in]
  (let [out (async/chan)]
    (async/go
      (while true
        (let [[group _events] (async/<! in)
              events (clean-events _events)
              new-events (atom [])]
          (doseq [event events]
            (let [result-chan (async/chan)
                  p (async-get
                     " "
                     (format photo-uri-template (:photo_album_id event) api-key)
                     result-chan)
                  [_ photo] (async/<!! result-chan)]
              (swap! new-events conj (assoc event :photos (clean-events photo)))))
          (async/go
            (async/>! out (assoc group :events @new-events))))))
    out))

;; expecting a string
(defn get-group-map
  [in]
  (let [out (async/chan)]
    (async/go (while true (get-group (async/<! in) out)))
    out))

;; expecting a string
(defn get-event-map
  [in]
  (let [out (async/chan)]
    (async/go (while true (get-event (async/<! in) out)))
    out))

(defn swap-group
  [in]
  (async/go
    (while true
      (swap! chapter-results conj (async/<! in))
      (if (= (count @chapter-results) @result-limit)
        (println "Chickens home to roost")
        (println ".. wait for it ..")))))

(def in-chan (async/chan))
(def group-out (get-group-map in-chan))
(def event-out (get-event-map group-out))
(def photo-out (get-photo-map event-out))
(swap-group photo-out)

(defn fetch-chapters
  [coll]
  (swap! result-limit + (count coll))
  (doseq [n coll]
    (async/>!! in-chan n)))


;;(async/onto-chan in-chan (take 2 chapter-urls))
;; (doseq [n (take 3 chapter-urls)] (async/>!! in-chan n))

;; (map :photo_id (:photos (first (:events (first @chapter-results)))))
