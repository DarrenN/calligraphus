(ns calligraphus.concur
  (:require [calligraphus.creds :as creds]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [cheshire.core :refer :all]))

(def group-uri-template "http://api.meetup.com/2/groups?radius=25.0&order=id&group_urlname=%s&desc=false&offset=0&photo-host=public&format=json&page=50&key=%s")
(def event-uri-template "https://api.meetup.com/2/events?&sign=true&photo-host=public&status=upcoming,past&group_id=%s&fields=photo_album_id&page=50&key=%s")
(def photo-uri-template "https://api.meetup.com/2/photos?&sign=true&photo-host=public&photo_album_id=%s&page=20&key=%s")

(defn make-group-uri
  [group]
  (format group-uri-template group creds/api-key))

(defn make-event-uri
  [group]
  (format event-uri-template (:id group) creds/api-key))

(defn make-photo-uri
  [event]
  (format photo-uri-template (:photo_album_id event) creds/api-key))

;; :body comes back as JSON so we need to convert to a keyworded map
(defn parse-response
  [resp]
  (assoc resp :body (walk/keywordize-keys (parse-string (:body resp)))))

(def chapter-urls ["papers-we-love" "papers-we-love-too" "Papers-We-Love-Boulder" "papers-we-love-london" "Papers-We-Love-in-saint-louis" "Papers-We-Love-Columbus" "Papers-We-Love-Berlin" "Doo-Things" "Papers-We-Love-Boston" "Papers-we-love-Bangalore/" "Papers-We-Love-DC" "Papers-We-Love-Montreal" "Papers-We-Love-Seattle" "Papers-We-Love-Toronto" "Papers-We-Love-Hamburg" "Papers-We-Love-Dallas" "Papers-We-Love-Chicago" "Papers-We-Love-Reykjavik" "Papers-We-Love-Vienna" "Papers-We-Love-Munich" "Papers-We-Love-Madrid"])

(def groups (atom []))
(def group-events (atom []))
(def event-photos (atom []))

(defn parse-groups
  [results]
  (doseq [result results]
    (when (= (:status result) 200)
      (swap! groups conj (first (get-in result [:body :results]))))))

(defn parse-events
  [results]
  (doseq [result results]
    (when (= (:status result) 200)
      (swap! group-events conj (get-in result [:body :results])))))

(defn parse-photos
  [results]
  (doseq [result results]
    (when (= (:status result) 200)
      (swap! event-photos conj (get-in result [:body :results])))))

(defn send-blast
  [set uri-fn parse-fn type]
  (doseq [urls set]
    (let [futures (doall (map #(http/get (uri-fn %)) urls))
          results (reduce #(conj %1 (parse-response (deref %2))) [] futures)
          limit (get-in (first results) [:headers :x-ratelimit-reset])]
      (log/info type (map (fn [r] [(:status r) (str "limit:" limit)]) results))
      (parse-fn results)
      (Thread/sleep (+ (* (Integer/parseInt limit) 1000) 300)))))

(defn get-groups
  [coll]
  (let [url-set (partition-all 20 coll)]
    (send-blast url-set make-group-uri parse-groups "Groups")))

(defn get-events
  [coll]
  (let [url-set (partition-all 20 coll)]
    (send-blast url-set make-event-uri parse-events "Events")))

(defn get-photos
  [coll]
  (let [url-set (partition-all 20 coll)]
    (send-blast url-set make-photo-uri parse-photos "Photos")))

(defn index-map
  [coll]
  (reduce #(assoc %1 (keyword (str (:id %2))) %2) {} coll))

(defn match-events
  [group-map events]
  (reduce #(assoc-in %1 [(keyword (str (get-in (first %2) [:group :id]))) :events] %2) group-map events))

(defn matcher
  [events photos]
  (let [event (first (filter #(= (:id %) (get-in (first photos) [:photo_album :event_id])) events))]
    (println events (get-in (first photos) [:photo_album :event_id]))
    (assoc event :photos photos)))

(defn match-photos
  [event-map photos]
  (reduce (fn [em photo]
            (let [event-id (keyword (-> (first photo) :photo_album :event_id))]
              (assoc-in em [event-id :photos] photo)))
          event-map
          photos))

(defn match-events-to-group
  [group-map event-map]
  (let [event-keys (keys event-map)]
    (reduce (fn [gm ek]
              (let [event (ek event-map)
                    group-id (keyword (str (get-in event-map [ek :group :id])))
                    group-events (get-in gm [group-id :events])]
                (assoc-in gm [group-id :events] (conj group-events event))))
            group-map
            event-keys)))

(defn get-em
  []
  (get-groups chapter-urls)
  (get-events @groups)
  (get-photos (remove #(nil? (:photo_album_id %)) (flatten @group-events)))
  (let [group-map (index-map @groups)
        event-map (index-map (flatten @group-events))
        event-photo-map (match-photos event-map @event-photos)
        final-map (match-events-to-group group-map event-photo-map)]
    (spit "/tmp/meetup0.json" (generate-string final-map))))
