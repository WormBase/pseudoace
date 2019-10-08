(ns pseudoace.utils-wbdb-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [environ.core :refer [env]]
   [pseudoace.utils :refer [wbdb-name *wbdb-name-env-key*]]))

(deftest test-wbdb-name

  (testing "Getting database name given an single argument `uri`."
    (are [expected s]
      (= expected (wbdb-name s))
      nil ""
      nil " "
      nil "http://www.wormbase.org/WS233"
      nil "https://www.wormbase.org/WS211"
      nil "datomic:ddb://us-east-1/WSABC"
      "WS999" "datomic:ddb://us-east-1/WS999/wormbase"
      "WS256" "datomic:dev://localhost:4334/WS256"
      "WS277" "datomic:ddb-local://localhost:80/shared/WS277"
      "WS9000" (io/resource ".config")))

  (testing "Getting database name from the environment (no arguments)."
    (is (= (wbdb-name)
            (wbdb-name (env *wbdb-name-env-key*))))
    ;; check we fallback to using ebconfig if env-key not set.
    (binding [*wbdb-name-env-key* (str ::fake-env-key)]
      (is (= (wbdb-name) "WS9000")))))
