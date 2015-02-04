(ns calligraphus.maps
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.pprint :as p]))

(def chapters
  ["foo"
   "bar"
   "baz"
   "quux"])

;; (defn chapter-create [s]
;;   (hash-map :chapter s))

;; (defn chapter-add-stub [m]
;;   (conj m {:stub "blah"}))

;; (def create-chapter-xform
;;   (comp
;;    (map chapter-create)
;;    (map chapter-add-stub)))

;; (def build-chapters-chan (async/chan 1 create-chapter-xform))
;; (def result-chan (async/reduce #(conj %1 %2) [] build-chapters-chan))

;; (async/onto-chan build-chapters-chan chapters)

;; (def hydrated (async/<!! result-chan))

(defn foo [v out-c]
  (async/go
    (async/>! out-c (hash-map :chapter v))))

(defn bar [in-c out-c]
  (async/go-loop []
    (let [v (async/<! in-c)]
      (when v
        (async/>! out-c (assoc v :bar [1 2 3]))))
    (recur)))

(defn baz [in-c out-c]
  (async/go-loop []
    (let [v (async/<! in-c)]
      (when v
        (async/>! out-c (assoc v :key (rand 1000)))))
    (recur)))

(def processed-chapters (atom []))

(def results-c (async/chan 1))

(defn main-loop [coll]
  (let [main-c (async/chan 1)
        foo-c (async/chan 1)]
    ;(baz baz-c results-c)
    (async/go-loop [v (async/<! main-c)]
      (when v
        (p/pprint v)
        (async/>! results-c v)
        (recur (async/<! main-c))))
    (async/onto-chan main-c coll)))

(def r-chan (async/reduce #(conj %1 %2) [] results-c))

(main-loop chapters)

(def r (async/go (async/<! r-chan)))
