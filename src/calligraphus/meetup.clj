(ns calligraphus.meetup
  (:require [calligraphus.creds :as creds]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [cheshire.core :refer :all]))

;; Mutable storage for API responses

(def groups (atom []))
(def group-events (atom []))
(def event-photos (atom []))

;; Endpoint URI templates

(def group-uri-template "http://api.meetup.com/2/groups?radius=25.0&order=id&group_urlname=%s&desc=false&offset=0&photo-host=public&format=json&page=50&key=%s")
(def event-uri-template "https://api.meetup.com/2/events?&sign=true&photo-host=public&status=upcoming,past&group_id=%s&fields=photo_album_id&page=50&key=%s")
(def photo-uri-template "https://api.meetup.com/2/photos?&sign=true&photo-host=public&photo_album_id=%s&page=20&key=%s")

;; Build correct URIs for endpoints from templates

(defn make-group-uri
  [url]
  (format group-uri-template url creds/api-key))

(defn make-event-uri
  [group]
  (format event-uri-template (:id group) creds/api-key))

(defn make-photo-uri
  [event]
  (format photo-uri-template (:photo_album_id event) creds/api-key))

(defn parse-response
  ":body comes back as JSON so we need to convert to a keyworded map"
  [resp]
  (assoc resp :body (walk/keywordize-keys (parse-string (:body resp)))))

(defn parse-groups
  "Only store 200 OK responses in groups atom"
  [results]
  (doseq [result results]
    (when (= (:status result) 200)
      (swap! groups conj (first (get-in result [:body :results]))))))

(defn parse-events
  "Only store 200 OK responses in group-events atom"
  [results]
  (doseq [result results]
    (when (= (:status result) 200)
      (swap! group-events conj (get-in result [:body :results])))))

(defn parse-photos
  "Only store 200 OK responses in event-photos atom"
  [results]
  (doseq [result results]
    (when (= (:status result) 200)
      (swap! event-photos conj (get-in result [:body :results])))))

(defn send-blast
  "We have to throttle concurrent requests against the API endpoints as per
  headers sent back in responses, otherwise we will get locked out for up to
  an hour."
  [set uri-fn parse-fn type]
  (doseq [urls set]
    (let [futures (doall (map #(http/get (uri-fn %)) urls))
          results (reduce #(conj %1 (parse-response (deref %2))) [] futures)
          limit (get-in (first results) [:headers :x-ratelimit-reset])]
      (log/info type (map (fn [r] [(:status r) (str "limit:" limit)]) results))
      (parse-fn results)
      (Thread/sleep (+ (* (Integer/parseInt limit) 1000) 300)))))

(defn get-groups
  "Fetch from /groups endpoint"
  [coll]
  (let [url-set (partition-all 20 coll)]
    (send-blast url-set make-group-uri parse-groups "Groups")))

(defn get-events
  "Fetch from /events endpoint"
  [coll]
  (let [url-set (partition-all 20 coll)]
    (send-blast url-set make-event-uri parse-events "Events")))

(defn get-photos
  "Fetch from /photos endpoint"
  [coll]
  (let [url-set (partition-all 20 coll)]
    (send-blast url-set make-photo-uri parse-photos "Photos")))

(defn index-map
  "Convert a vector of maps to a map keyed by :id"
  [coll]
  (reduce #(assoc %1 (keyword (str (:id %2))) %2) {} coll))

(defn match-photos
  "Use event id within photo vectors to attach to correct event entry"
  [event-map photos]
  (reduce (fn [em photo]
            (let [event-id (keyword (-> (first photo) :photo_album :event_id))]
              (assoc-in em [event-id :photos] photo)))
          event-map
          photos))

(defn match-events-to-group
  "Use group id within events to find correct group entry"
  [group-map event-map]
  (let [event-keys (keys event-map)]
    (reduce (fn [gm ek]
              (let [event (ek event-map)
                    group-id (keyword (str (get-in event-map [ek :group :id])))
                    group-events (get-in gm [group-id :events])]
                (assoc-in gm [group-id :events] (conj group-events event))))
            group-map
            event-keys)))

(defn get-chapters
  "Take a vector of chapter url-names and return meetup.com data as a map"
  [chapters]
  (get-groups chapters)
  (get-events @groups)
  (get-photos (remove #(nil? (:photo_album_id %)) (flatten @group-events)))
  (let [group-map (index-map @groups)
        event-map (index-map (flatten @group-events))
        event-photo-map (match-photos event-map @event-photos)
        final-map (match-events-to-group group-map event-photo-map)]
    ;; Swap urlname for :id keys
    (dissoc (reduce-kv #(assoc %1 (:urlname %3) %3) {} final-map) "null")))
