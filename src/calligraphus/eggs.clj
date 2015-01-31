(ns calligraphus.eggs
  (:require [clojure.core.async :as async]
            [clojure.string :as str]))

(def green-eggs-n-ham
  ["in the rain"
   "on a train"
   "in a box"
   "with a fox"
   "in a house"
   "with a mouse"
   "here or there"
   "anywhere"])

(defn i-do-not-like-them [s]
  (format "I would not eat them %s." s))

(defn try-them [s]
  (str/replace s  #" not" ""))

(def sam-i-am-xform
  (comp
   (map i-do-not-like-them)
   (map try-them)))

(def sam-i-am-chan (async/chan 1 sam-i-am-xform))
(def result-chan (async/reduce #(str %1 %2 " ") "" sam-i-am-chan))

(async/onto-chan sam-i-am-chan green-eggs-n-ham)

(def i-like-them (async/<!! result-chan))
