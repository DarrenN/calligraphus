(ns calligraphus.facebook
  (:require [calligraphus.creds :as creds]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [cheshire.core :refer :all]))

(defn get-chapters
  [chapters]
  {:facebook chapters})
