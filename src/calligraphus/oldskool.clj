(ns calligraphus.oldskool
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [cheshire.core :refer :all]
            [throttler.core :refer [throttle-chan chan-throttler]]
            [clojure.core.async :as a :refer [>! <! >!! <!! go go-loop chan buffer
                                              close! thread alts! alts!!
                                              timeout]]))

(def api-key "4113124f79194d5f676f152d51377b54")
(def delay-req (atom 0))
(def results (atom []))
(def group-uri-template "http://api.meetup.com/2/groups?radius=25.0&order=id&group_urlname=%s&desc=false&offset=0&photo-host=public&format=json&page=50&key=%s")
(def event-uri-template "https://api.meetup.com/2/events?&sign=true&photo-host=public&status=upcoming,past&group_id=%s&fields=photo_album_id&page=50&key=%s")
(def photo-uri-template "https://api.meetup.com/2/photos?&sign=true&photo-host=public&photo_album_id=%s&page=20&key=%s")

(defn make-group-uri
  [group]
  (format group-uri-template group api-key))

(defn make-event-uri
  [id]
  (format event-uri-template id api-key))

(defn make-photo-uri
  [id]
  (format photo-uri-template id api-key))

;; If we're nearing the limit on our request window, we need to increase the
;; timeout to ensure we don't cross the limit. The API tells us what that value
;; should be
(defn throttle-requests
  [headers]
  (let [limit (:x-ratelimit-limit headers)
        remain (:x-ratelimit-remaining headers)
        reset (:x-ratelimit-reset headers)]
    (if (<= (Integer/parseInt remain) 1)
      (reset! delay-req (inc reset))
      (reset! delay-req 1))))

;; :body comes back as JSON so we need to convert to a keyworded map
(defn parse-response
  [resp]
  (assoc resp :body (walk/keywordize-keys (parse-string (:body resp)))))

;; Check response for throttling and log some information
(defn process-response
  [response]
  (let [[url resp] response
        status (:status resp)]
    (throttle-requests (:headers resp))
    (if (= 200 status)
      (do (log/info url status "Current delay:" @delay-req)
          [status url resp])
      (do (log/error "Not OK" status url)
          [status url resp]))))

(defn async-get
  [url c]
  (http/get url #(go (<! (timeout (* @delay-req 1000)))
                     (>! c [url (parse-response %)]))))

;; URLs come in and responses go out
(defn url-machine
  []
  (let [in (chan)
        out (chan)]
    (go-loop [u (<! in)]
      (when u
        (http/get u #(go (<! (timeout (* @delay-req 1000)))
                         (>! out [u (parse-response %)]))))
      (recur (<! in)))
    [in out]))

;; HACK: I do not like this one bit. There has to be a cleaner way
(defn photo-machine
  []
  (let [pin (chan)
        pout (chan)]
    (go-loop [events (<! pin)]
      (when events
        (let [res (atom [])]
          (loop [es events]
            (if (> (count es) 0)
              (let [event (first es)
                    photo-id (:photo_album_id event)
                    [in out] (url-machine)]
                ;; Some events don't have photos, so we skip them
                (if photo-id
                  (do (>!! in (make-photo-uri photo-id))
                      (let [[status url resp] (process-response (<!! out))
                            event (assoc event :photos (get-in resp [:body :results]))]
                        (if (= status 200)
                          (do (swap! res conj event)
                              (recur (rest es)))
                          (recur (rest es)))))
                  (recur (rest es))))
              (>!! pout @res)))))
      (recur (<! pin)))
    [pin pout]))

;; Loop through a vector of url strings and send to the /groups endpoint
(defn fetch-groups
  [urls out]
  (let [res (atom [])
        response (chan)]
    (doseq [url urls]
      (async-get (make-group-uri url) response)
      (let [[status url resp] (process-response (<!! response))]
        (when (= status 200)
          (swap! res conj (first (get-in resp [:body :results]))))))
    (>!! out @res)))

;; Loop through a vector of group maps, extract the :id and send to the /events
;; endpoint
(defn fetch-events
  [groups out]
  (let [res (atom [])
        response (chan)]
    (doseq [group groups]
      (async-get (make-event-uri (:id group)) response)
      (let [[status url resp] (process-response (<!! response))]
        (when (= status 200)
          (swap! res
                 conj
                 (assoc group :events (get-in resp [:body :results]))))))
    (>!! out @res)))

(defn fetch-photo-album
  [events out]
  (let [res (atom [])
        response (chan)]
    (doseq [event events]
      (if (:photo_album_id event)
        (do (async-get (make-photo-uri (:photo_album_id event)) response)
            (let [[status url resp] (process-response (<!! response))]
              (when (= status 200)
                (swap! res
                       conj
                       (assoc event :photos (get-in resp [:body :results]))))))
        (swap! res conj event)))
    (>!! out @res)))

(defn fetch-photos2
  [groups out]
  (let [res (atom [])
        response (chan)]
    (doseq [group groups]
      (let [events (:events group)
            eres (atom [])]
        (if events
          (do (doseq [event events]
                (if (:photo_album_id event)
                  (do (async-get (make-photo-uri (:photo_album_id event)) response)
                      (let [[status url resp] (process-response (<!! response))]
                        (when (= status 200)
                          (swap! eres
                                 conj
                                 (assoc event :photos (get-in resp [:body :results]))))))
                  (swap! eres conj event)))
              (swap! res conj (assoc group :events @eres)))
          (swap! res conj group))))
    (>!! out @res)))

(defn fetch-photos
  [groups out]
  (let [res (atom [])]
    (loop [gs groups]
      (if (> (count gs) 0)
        (let [group (first gs)
              events (:events group)
              [in out] (photo-machine)]
          (>!! in events)
          (let [evs (<!! out)
                group (assoc group :events evs)]
            (swap! res conj group)
            (recur (rest gs))))
        (>!! out @res)))))

;; Link a function to channel and return a new channel for results
(defn make-pipe
  [func in]
  (let [out (chan)]
    (go
      (while true
        (func (<! in) out)))
    out))

(defn print-results
  [in]
  (go
    (while true
      (reset! results (<! in))
      (println "All Done!"))))

(def main-chan (chan))
(def res1-out (make-pipe fetch-groups main-chan))
(def res2-out (make-pipe fetch-events res1-out))
(def res3-out (make-pipe fetch-photos2 res2-out))
(print-results res3-out)

(def in-chan (chan))
;;(def slow-chan (throttle-chan in-chan 1 :second 9))

(def chapter-urls ["papers-we-love" "papers-we-love-too" "Papers-We-Love-Boulder" "papers-we-love-london" "Papers-We-Love-in-saint-louis" "Papers-We-Love-Columbus" "Papers-We-Love-Berlin" "Doo-Things" "Papers-We-Love-Boston" "Papers-we-love-Bangalore/" "Papers-We-Love-DC" "Papers-We-Love-Montreal" "Papers-We-Love-Seattle" "Papers-We-Love-Toronto" "Papers-We-Love-Hamburg" "Papers-We-Love-Dallas" "Papers-We-Love-Chicago" "Papers-We-Love-Reykjavik" "Papers-We-Love-Vienna" "Papers-We-Love-Munich"])

(def make-slow-chan (chan-throttler 1 :second))

(def c1 (chan))
(def c2 (chan))
(def c3 (chan))
(def slow-1 (make-slow-chan c1))
(def slow-2 (make-slow-chan c2))
(def slow-3 (make-slow-chan c3))

(def bigmap (atom {}))

(def groups (atom []))
(def group-events (atom []))
(def event-photos (atom []))

(go
  (doseq [u chapter-urls]
    (async-get (make-group-uri u) c1)))

(go-loop [v (<! slow-1)]
  (when v
    (let [[url resp] v
          status (:status resp)
          group (first (get-in resp [:body :results]))
          id (:id group)]
      (log/info "Group Status" status url)
      (when (= status 200)
        (swap! bigmap assoc (keyword (str id)) group)
        (async-get (make-event-uri id) c2)))
    (recur (<! slow-1))))

(go-loop [v (<! slow-2)]
  (when v
    (let [[url resp] v
          status (:status resp)
          events (get-in resp [:body :results])]
      (log/info "Event Status" status url)
      (when events
        (let [group-id (get-in (first events) [:group :id])]
          (swap! bigmap update-in (keyword (str group-id)) assoc :events events)
          (doseq [event events]
            (when (:photo_album_id event)
              (async-get (make-photo-uri (:photo_album_id event)) c3))))))
    (recur (<! slow-2))))

(go-loop [v (<! slow-3)]
  (when v
    (let [[url resp] v
          status (:status resp)
          photos (get-in resp [:body :results])]
      (log/info "Photo Status" status url)
      (when photos
        (let [group-id (get-in (first photos) [:photo_album :group_id])
              event-id (get-in (first photos) [:photo_album :event_id])
              group (get (keyword group-id) @bigmap)])
        (swap! event-photos conj photos)))
    (recur (<! slow-3))))

;;(time (while (not (nil? (<!! slow-chan)))))

;; (>!! main-chan (take 40 (cycle ["papers-we-love" "papers-we-love-too" "Papers-We-Love-Dallas" "Papers-We-Love-Munich"])))

;;(url-fetch2 ["http://v25media.com" "http://darrennewton.com" "http://metahairball.com" "http://www.google.com/234523453"])
