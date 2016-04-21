(ns pseudoace.test-schemata
  (:use [clojure.test])
  (:require [datomic.api :as d]
            [pseudoace.core :as core]
            [pseudoace.schemata :as schemata]))

(def db-uri "datomic:mem://wb-test")

(defn db-created [test-fn]
  (d/create-database db-uri)
  (test-fn))

(use-fixtures :each db-created)

(defn- check-installed-attr-count
  "An installed schema will have over 2500 attributes installed."
  [db]
  (let [n-attrs-inst (d/q '[:find (count ?e) .
	               :where [_ :db.install/attribute ?e]] db)]
    (is (>= n-attrs-inst 2500))))

(defn- check-pace-metaschema
  "Check we can query against the `:pace` schema."
  [db]
  (is (= 1 (d/q '[:find (count ?e) .
                  :where [?e :pace/identifies-class "Gene"]] db))))

(deftest test-install
  (let [main-schema (core/generate-schema)
        con (d/connect db-uri)]
    (schemata/install con main-schema)
    (let [db (d/db con)]
      (check-installed-attr-count db)
      (check-pace-metaschema db))))
