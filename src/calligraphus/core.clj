(ns calligraphus.core
  (:require [calligraphus.meetup :as meetup]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [clojure.pprint :as pretty]
            [clojure.tools.logging :as log]
            [clj-yaml.core :as yaml]
            [cheshire.core :refer :all]))

; Load chapter data
(def in "/Users/shibuya/github/papers-we-love.github.io/source/chapters.yml")
(def out "/Users/shibuya/github/papers-we-love.github.io/data/meetup/chapters.yml")
(def chapter-yaml (io/file in))
(def chapters (yaml/parse-string (slurp chapter-yaml)))

; List of url formatted chapter names - used to hit API for group info
(def chapter-urls (remove nil? (map :meetup_url chapters)))

;; (spit "/tmp/meetup0.json" (generate-string final-map))
;; /Users/shibuya/github/papers-we-love.github.io/source/chapters.yml



;; (spit out (yaml/generate-string (meetup/get-chapters chapter-urls)))
