(ns calligraphus.sinky
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [>! <! >!! <!! go go-loop chan buffer
                                              close! thread alts! alts!!
                                              timeout]]))

(defn hotdog-machine
  []
  (let [in (chan)
        out (chan)]
    (go (<! in)
        (>! out "hotdog"))
    [in out]))

(defn hotdog-machine-v2
  [hotdog-count]
  (let [in (chan)
        out (chan)]
    (go-loop [hc hotdog-count]
      (if (> hc 0)
        (let [input (<! in)]
          (if (= 3 input)
            (do (>! out "hotdog")
                (recur (dec hc)))
            (do (>! out "wilted lettuce")
                (recur hc))))
        (do (close! in)
            (close! out))))
    [in out]))

(let [[in out] (hotdog-machine-v2 2)]
  (>!! in "pocket-lint")
  (println (<!! out))

  (>!! in 3)
  (println (<!! out))

  (>!! in 3)
  (println (<!! out))

  (>!! in 3)
  (println (<!! out)))

(let [c1 (chan)
      c2 (chan)
      c3 (chan)]
  (go (>! c2 (str/upper-case (<! c1))))
  (go (>! c3 (str/reverse (<! c2))))
  (go (println (<! c3)))
  (>!! c1 "redrum"))

(defn upload
  [headshot c]
  (go (Thread/sleep (rand 100))
      (>! c headshot)))

(let [c1 (chan)
      c2 (chan)
      c3 (chan)]
  (upload "funs.jpg" c1)
  (upload "suns.jpg" c2)
  (upload "buns.jpg" c3)
  (let [[headshot channel] (alts!! [c1 c2 c3])]
    (println "Sending headshot notification for" headshot)))
