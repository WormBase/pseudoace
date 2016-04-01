(ns pseudoace.test-utils
  (:require
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
