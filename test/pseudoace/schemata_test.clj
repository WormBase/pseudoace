(ns pseudoace.schemata-test
  (:require
   [clj-time.coerce :refer [to-date]]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [datomic.api :as d]
   [pseudoace.cli :as cli]
   [pseudoace.ts-import :refer [latest-transaction-date]]
   [pseudoace.schemata :as schemata]))

(def db-uri "datomic:mem://wb-test")

(def annotated-models-path "/tmp/latest-annotated-models.wrm")

(defn db-created [test-fn]
  (d/create-database db-uri)
  (test-fn)
  (d/delete-database db-uri))

(def annotated-models-uri
  (str "https://raw.githubusercontent.com/"
       "WormBase/wormbase-pipeline/master/"
       "wspec/models.wrm.annot"))

(defn slurp-latest-annotated-models [_]
  (with-open [in (io/input-stream annotated-models-uri)
              out (io/output-stream annotated-models-path)]
    (io/copy in out)))

(use-fixtures :each db-created)

(use-fixtures :once slurp-latest-annotated-models)

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

(defn- check-schema
  [db]
  (doseq [check [check-installed-attr-count
                 check-pace-metaschema
                 check-schema-tx-time-is-early]]
    (check db)))

(defn- do-install
  [& {:keys [no-locatables no-fixups]
      :or {no-locatables false
           no-fixups false}}]
  (let [main-schema (cli/generate-schema
                     :models-filename annotated-models-path)
        con (d/connect db-uri)]
    (schemata/install con
                      main-schema
                      :no-locatables no-locatables
                      :no-fixups no-fixups)
    con))

(deftest test-install
  (let [con (do-install)
        db (d/db con)]
    (check-schema db)))

(deftest test-install-no-locatables
  (let [con (do-install :no-locatables true)
        db (d/db con)]
    (check-schema db)
    (is (= nil (d/q '[:find (count ?e) .
                      :where [?e :pace/use-ns #{"locatable"}]] db)))))

(deftest test-install-no-fixups
  (let [con (do-install :no-fixups true)
        db (d/db con)]
    (check-schema db)))

(deftest test-install-no-locatables-or-fixups
  (let [con (do-install :no-locatables true :no-fixups true)
        db (d/db con)]
    (check-schema db)))
