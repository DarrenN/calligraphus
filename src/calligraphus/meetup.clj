(ns calligraphus.meetup
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [cheshire.core :refer :all]
            [environ.core :refer [env]]))

(def meetup-api-key (env :meetup-api-key))

;; Endpoint URI templates

(def group-uri-template "http://api.meetup.com/2/groups?radius=25.0&order=id&group_urlname=%s&desc=false&offset=0&photo-host=public&format=json&page=50&key=%s")
(def event-uri-template "https://api.meetup.com/2/events?&sign=true&photo-host=public&status=upcoming,past&group_id=%s&fields=photo_album_id&page=50&key=%s")
(def photo-uri-template "https://api.meetup.com/2/photos?&sign=true&photo-host=public&photo_album_id=%s&page=20&key=%s")

;; Build correct URIs for endpoints from templates

(defn make-group-uri
  [url]
  (format group-uri-template url meetup-api-key))

(defn make-event-uri
  [group]
  (format event-uri-template (:id group) meetup-api-key))

(defn make-photo-uri
  [photo-id]
  (format photo-uri-template photo-id meetup-api-key))

(defn keywordize-response
  ":body comes back as JSON so we need to convert to a keyworded map"
  [resp]
  (assoc resp :body (walk/keywordize-keys (parse-string (:body resp)))))

(defn throttle-request
  "Check the x-ratelimit headers on every response and sleep if we're near
  the last available request for our time slot. Prevents us getting locked out."
  [url]
  (let [future (http/get url)
        response @future
        {:keys [x-ratelimit-reset x-ratelimit-limit x-ratelimit-remaining]} (:headers response)]
    (log/debug [(:status response) (str "Remaining:" x-ratelimit-remaining)])
    ;; If we're withing one request of the limit, hit the brakes
    (when (= 1 (Integer/parseInt x-ratelimit-remaining))
      (log/info "Hit limit, throttling for" x-ratelimit-reset)
      (Thread/sleep (+ 300 (* 1000 (Integer/parseInt x-ratelimit-reset)))))
    (keywordize-response response)))

(defn get-group
  "Retrieve group data, if not available pass along an empty map"
  [urlname]
  (let [resp (throttle-request (make-group-uri urlname))
        status (:status resp)
        group (first (get-in resp [:body :results]))]
    (if-not (= 200 status)
      (do (log/warn "Group endpoint returned" status "for" urlname)
          {})
      (hash-map urlname group))))

(defn get-events
  "If group has events assoc them onto the map"
  [group-map]
  (let [urlname (first (keys group-map))
        resp (throttle-request (make-event-uri (get group-map urlname)))
        status (:status resp)
        events (get-in resp [:body :results])]
    (if-not (= 200 status)
      (do (log/warn "Event endpoint returned" status "for" urlname)
          group-map)
      (assoc-in group-map [urlname :events] events))))

(defn get-photo-albums
  "If the event has a photo_album_id then assoc it on"
  [event]
  (let [photo-id (:photo_album_id event)]
    (if (nil? photo-id)
      event
      (let [resp (throttle-request (make-photo-uri photo-id))
            status (:status resp)
            photos (get-in resp [:body :results])]
        (if-not (= 200 status)
          (do (log/warn "Photos endpoint returned" status "for" photo-id)
              event)
          (assoc event :photos photos))))))

(defn get-photos
  "Map over the events collection and add photo-albums"
  [group-map]
  (let [urlname (first (keys group-map))
        events (get-in group-map [urlname :events])
        events-with-photos (map get-photo-albums events)]
    (assoc-in group-map [urlname :events] events-with-photos)))

(defn build-chapter-map
  "Get a map of chapter information from chapter's urlname"
  [m urlname]
  (let [c (-> urlname
              (get-group)
              (get-events)
              (get-photos))]
    (conj m c)))

(defn get-chapters
  "Take a vector of chapter urlnames and build a large map keyed on urlname"
  [chapters]
  (reduce #(build-chapter-map %1 %2) {} (map :url chapters)))
