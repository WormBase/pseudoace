(ns pseudoace.test-utils
  (:require
   [clj-time.coerce :refer (from-date)]
   [clj-time.core :refer (before? after?)]
   [clojure.instant :refer (read-instant-date)]
   [clojure.test :as t]
   [pseudoace.utils :as utils]))

(t/deftest test-vmap
  (t/is (= (utils/vmap "onearg") {}))
  (t/is (= (utils/vmap "" "") {"" ""}))
  (t/is (= (utils/vmap "1" "2" "3") {"1" "2"}))
  (t/is (= (utils/vmap 1 2 3) {1 2}))
  (t/is (= (utils/vmap :a 1 :b 2) {:a 1 :b 2}))
  (t/is (= (utils/vmap 1 2 3 4) {1 2, 3 4})))

(t/deftest test-vmap-if
  (t/is (nil? (utils/vmap-if 1 nil)))
  (t/is (= (utils/vmap-if 1 nil 2 "goal" 3 "score")
           {2 "goal" 3 "score"})))

(t/deftest test-merge-pairs
  (t/is (nil? (utils/merge-pairs [])))
  (t/is (= (utils/merge-pairs [["A" "B"]]) {"A" #{"B"}}))
  (t/is (= (utils/merge-pairs [["A" "B"]
                               ["C" "D"]
                               ["A" "C"]
                               ["D" "A"]
                               ["D" "C"]])
           {"A" #{"B" "C"}
            "C" #{"D"}
            "D" #{"A" "C"}})))

(t/deftest test-filter-by-date
  (let [ex-date (from-date (read-instant-date "2012-10-10"))
        coll ["2016-01-02.foo.bar"
              "2010-02-03.bar.foo"
              "2005-11-21.spam.eggs"
              "2013-04-12.badger"
              "2011-03-04.baz.say"]]
    (t/is (= (utils/filter-by-date coll ex-date before?)
             ["2005-11-21.spam.eggs"
              "2010-02-03.bar.foo"
              "2011-03-04.baz.say"]))
    (t/is (= (utils/filter-by-date coll ex-date after?)
             ["2013-04-12.badger"
              "2016-01-02.foo.bar"]))))

(t/deftest test-package-version
  (t/testing "Getting a package version"
    (let [is-version (partial re-matches #"^\d\.\d.\d?(-SNAPSHOT|)")
          pkg-version #(or (utils/package-version %) "")]
      (is-version (pkg-version "wormbase/pseudoace"))
      (is-version (pkg-version "org.clojure/core.cache"))
      (is-version (pkg-version "org.clojure/clojure")))))

