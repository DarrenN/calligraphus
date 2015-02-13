(ns calligraphus.queues
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [clojure.core.async :as a :refer [>! <! >!! <!! go go-loop chan buffer
                                              close! thread alts! alts!!
                                              timeout]]))

(defn append-to-file
  [filename s]
  (spit filename s :append true))

(defn format-quote
  [quote]
  (str "=== BEGINE QUOTE ===\n" quote "=== END QUOTE ===\n\n"))

(defn random-quote
  []
  (format-quote (slurp "http://www.iheartquotes.com/api/v1/random")))

(defn snag-quotes
  [filename num-quotes]
  (let [c (chan)]
    (go (while true (append-to-file filename (<! c))))
    (dotimes [n num-quotes] (go (>! c (random-quote))))))

(defn upper-caser
  [in]
  (let [out (chan)]
    (go (while true
          (println "upper")
          (>! out (str/upper-case (<! in)))))
    out))

(defn reverser
  [in]
  (let [out (chan)]
    (go (while true (>! out (str/reverse (<! in)))))
    out))

(defn printer
  [in]
  (go (while true (println (<! in)))))

(def in-chan (chan))
(def upper-case-out (upper-caser in-chan))
(def reverser-out (reverser upper-case-out))
(printer reverser-out)

(let [urls ["http://apple.com" "http://google.com"
            "http://v25media.com"]
      ;; send the request concurrently (asynchronously)
      futures (doall (map http/get urls))]
  (doseq [resp futures]
    ;; wait for server response synchronously
    (println (:headers @resp) " status: " (:status @resp))))
