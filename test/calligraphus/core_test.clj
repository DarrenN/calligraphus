(ns calligraphus.core-test
  (:require [clojure.test :refer :all]
            [calligraphus.core :refer :all]
            [calligraphus.meetup :as meetup]
            [calligraphus.facebook :as facebook]))

(def meetup-urls '({:api "meetup", :url "http://meetup.com/foo"}
                   {:api "meetup", :url "http://meetup.com/bar"}
                   {:api "meetup", :url "http://meetup.com/baz"}))

(def facebook-urls '({:api "facebook", :url "http://facebook.com/foo"}
                     {:api "facebook", :url "http://facebook.com/bar"}
                     {:api "facebook", :url "http://facebook.com/baz"}))

(def busted-urls '({:api "", :url "http://twiiter.com/foo"}))

(deftest test-process-url
  (testing "process-url returns maps"
    (is (= (process-url "foo") {:api "meetup", :url "foo"})
        "process-url returns a meetup map if not a real url")

    (is (= (process-url "http://facebook.com/foo")
           {:api "facebook", :url "http://facebook.com/foo"})
        "process-url returns a facebook map")

    (is (= (process-url "http://twitter.com/foo")
           {:api "", :url "http://twitter.com/foo"})
        "process-url returns empty :api if not matched")))

(deftest test-api-dispatch
  (testing "api-dispatch calls different ns/functions depending on :api"
    (let [mchapters (atom '())
          fchapters (atom '())]
      (with-redefs [meetup/get-chapters (fn [chaps] (reset! mchapters chaps))
                    facebook/get-chapters (fn [chaps] (reset! fchapters chaps))]
        (let [resp1 (api-dispatch meetup-urls)
              resp2 (api-dispatch facebook-urls)]
          (is (= @mchapters meetup-urls)
              "Meetup chapters sent to meetup/get-chapters")
          (is (= @fchapters facebook-urls)
              "Facebook chapters sent to facebook/get-chapters")
          (is (thrown? IllegalArgumentException (api-dispatch busted-urls))
              "Busted chapters throw and exception in the multimethod"))))))
