(ns pseudoace.schemata
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.api :as d])
  (:import java.io.PushbackReader))

(def earliest-tx-timestamp #inst "1970-01-01T00:00:01")

(defn mark-tx-early
  "Marks mapping `item` with an early timestamp."
  [schema]
  (conj schema
        {:db/id (d/tempid :db.part/tx)
         :db/txInstant earliest-tx-timestamp}))

(def ^:private edn-read (partial edn/read {:readers *data-readers*}))

(defn idents-by-ns [db ns-name]
  (sort (d/q '[:find [?ident ...]
               :in $ ?ns-name
               :where
               [?e :db/ident ?ident]
               [_ :db.install/attribute ?e]
               [(namespace ?ident) ?ns]
               [(= ?ns ?ns-name)]]
             db ns-name)))

(defn schema-from-db [db]
  (->> (d/q
        '[:find [?schema-attr ...]
          :where [:db.part/db :db.install/attribute ?schema-attr]]
        db)
       (map (partial d/entity db))
       (group-by (comp namespace :db/ident))))

(defn read-edn-schema
  "Reads the EDN schemata from a resource.
  Throws an exception if `schema-kw` does not identify an existant
  schema resource. Returns a sequence of mappings that can be
  transacted into a datomic database."
  [schema-kw]
  (let [schema-name (name schema-kw)
        resource-path (str "schemata/" schema-name ".edn")
        schema (some->> resource-path
                        (io/resource)
                        (io/reader)
                        (PushbackReader.)
                        (edn-read))]
    (when-not schema
      (throw (ex-info "Schema resource :path not found"
                      {:path resource-path})))
    schema))

(defn- transact-silenced
  "Tranact the entity `tx` using `conn`.

  Suppresses the (potentially-large) report if it succeeds."
  [conn tx]
  @(d/transact conn tx)
  nil)

(defn install
  "Installs the various schemata with datomic connection `conn`.

  Each schema item will be marked with a date representing the
  earliest possible instant in the system.
  The order the various schemata are transacted in is important."
  [conn main-schema & {:keys [no-locatables no-fixups]
                       :or {no-locatables false
                            no-fixups false}}]
  ;; Built-in schemas
  ;; * Use the d/tempid function to give temporary ids for
  ;; * Include explicit 1970-01-01 timestamps.
  (let [transact (partial transact-silenced conn)
        transact-schema #(-> %
                             (mark-tx-early)
                             (transact))
        base-schemas (map read-edn-schema [:meta :base-types])]
    (doseq [base-schema base-schemas]
      (transact-schema base-schema))
    (if-not no-locatables
      (transact-schema (read-edn-schema :locatables)))
    (transact-schema main-schema)
    (if-not no-locatables
      (let [schema-kwds [:locatable-extras :top-level-locatable-fixups]]
        (doseq [kw schema-kwds]
          (transact-schema (read-edn-schema kw)))))
    (if-not no-fixups
      (transact-schema (read-edn-schema :component-xref-fixups)))))
