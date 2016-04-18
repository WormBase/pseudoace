(ns pseudoace.test-utils
  (:require
   [clj-time.coerce :refer (from-date)]
   [clj-time.core :refer (before? after?)]
   [clojure.instant :refer (read-instant-date)]
   [clojure.test :refer (deftest is)]
   [pseudoace.utils :as utils]))


(deftest test-vmap
  (is (= (utils/vmap "onearg") {}))
  (is (= (utils/vmap "" "") {"" ""}))
  (is (= (utils/vmap "1" "2" "3") {"1" "2"}))
  (is (= (utils/vmap 1 2 3) {1 2}))
  (is (= (utils/vmap :a 1 :b 2) {:a 1 :b 2}))
  (is (= (utils/vmap 1 2 3 4) {1 2, 3 4})))

(deftest test-vmap-if
  (is (nil? (utils/vmap-if 1 nil)))
  (is (= (utils/vmap-if 1 nil 2 "goal" 3 "score")
         {2 "goal" 3 "score"})))

(deftest test-merge-pairs
  (is (nil? (utils/merge-pairs [])))
  (is (= (utils/merge-pairs [["A" "B"]]) {"A" #{"B"}}))
  (is (= (utils/merge-pairs [["A" "B"]
                             ["C" "D"]
                             ["A" "C"]
                             ["D" "A"]
                             ["D" "C"]])
         {"A" #{"B" "C"}
          "C" #{"D"}
          "D" #{"A" "C"}})))

(deftest test-filter-by-date
  (let [ex-date (from-date (read-instant-date "2012-10-10"))
        coll ["2016-01-02.foo.bar"
              "2010-02-03.bar.foo"
              "2005-11-21.spam.eggs"
              "2013-04-12.badger"
              "2011-03-04.baz.say"]]
    (is (= (utils/filter-by-date coll ex-date before?)
           ["2005-11-21.spam.eggs"
            "2010-02-03.bar.foo"
            "2011-03-04.baz.say"]))
    (is (= (utils/filter-by-date coll ex-date after?)
           ["2013-04-12.badger"
            "2016-01-02.foo.bar"]))))
