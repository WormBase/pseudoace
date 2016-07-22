(ns pseudoace.test-schemata
  (:use [clojure.test])
  (:require
   [clj-time.coerce :refer (to-date)]
   [clojure.java.io :as io]
   [clojure.instant :refer (read-instant-date)]
   [datomic.api :as d]
   [pseudoace.core :as core]
   [pseudoace.ts-import :refer (latest-transaction-date)]
   [pseudoace.schemata :as schemata]))

(def db-uri "datomic:mem://wb-test")

(def annotated-models-path "/tmp/latest-annotated-models.wrm")

(defn db-created [test-fn]
  (d/create-database db-uri)
  (test-fn))

(def annotated-models-uri
  (str "https://raw.githubusercontent.com/"
       "WormBase/wormbase-pipeline/master/"
       "wspec/models.wrm.annot"))

(defn slurp-latest-annotated-models []
  (with-open [in (io/input-stream annotated-models-uri)
              out (io/output-stream annotated-models-path)]
    (io/copy in out)))

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

(defn- check-schema-tx-time-is-early
  "Check datoms in the database are suitably old."
  [db]
  (let [ltd (-> db latest-transaction-date to-date (.getTime))
        exp (.getTime schemata/earliest-tx-timestamp)]
    (is (<= ltd exp))))

(deftest test-install
  (slurp-latest-annotated-models)
  (let [main-schema (core/generate-schema
                     :models-filename annotated-models-path)
        con (d/connect db-uri)]
    (schemata/install con main-schema)
    (let [db (d/db con)]
      (check-installed-attr-count db)
      (check-pace-metaschema db)
      (check-schema-tx-time-is-early db))))
