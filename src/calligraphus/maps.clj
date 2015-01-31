(ns calligraphus.maps
  (:require [clojure.core.async :as async]
            [clojure.string :as str]))

(def chapters
  ["foo"
   "bar"
   "baz"
   "quux"])

(defn chapter-create [s]
  (hash-map :chapter s))

(defn chapter-add-stub [m]
  (conj m {:stub "blah"}))

(def create-chapter-xform
  (comp
   (map chapter-create)
   (map chapter-add-stub)))

(def build-chapters-chan (async/chan 1 create-chapter-xform))
(def result-chan (async/reduce #(conj %1 %2) [] build-chapters-chan))

(async/onto-chan build-chapters-chan chapters)

(def hydrated (async/<!! result-chan))
