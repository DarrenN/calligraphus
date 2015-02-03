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
(def api-key "512d751a3e41262c3e387533222581f")

(def chapter-results (atom []))

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
(defn async-get [url c]
  (http/get url #(async/go
                   (async/<! (async/timeout 1000)) ; throttle the reqs
                   (async/>! c %))))

(defn get-group [group c]
  (async-get (format group-uri-template group api-key) c))

(defn get-event [group c]
  (let [group_url (:id group)]
    (async-get (format event-uri-template group_url api-key) c)))

;; Take a group map, pull out the events vec and
;; make a photo_album_id request for each event, associng
;; it onto the event, into a new events vec, then assoc
;; that back onto the group and toss into a channel
(defn get-photos [group c]
  (let [new-events (atom [])
        events (:events group)]
    (doseq [event events]
      (let [result-chan (async/chan)
            p (async-get
               (format photo-uri-template (:photo_album_id event) api-key)
               result-chan)
            photo-result (clean-events (async/<!! result-chan))
            e (assoc event :photos photo-result)]
        (swap! new-events conj e)))
    (async/go
      (async/>! c (assoc group :events @new-events)))))

;; Send a chapter-url through a set of core.async channels to build up a big
;; vector of maps for each chapter
(doseq [group (take 5 chapter-urls)]
  (let [group-chan (async/chan)
        event-chan (async/chan)
        photo-chan (async/chan)
        g (get-group group group-chan)
        group-result (clean-group (async/<!! group-chan))
        e (get-event group-result event-chan)
        event-result (clean-events (async/<!! event-chan))
        ge (assoc group-result :events event-result)
        p (get-photos ge photo-chan)
        photo-result (async/<!! photo-chan)]
    (swap! chapter-results conj photo-result)))
