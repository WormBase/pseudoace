(ns pseudoace.schema-datomic
  "Provides functions for transforming native datomic schema into a datomic-schema representation."
  (:require [datomic.api :as d]))

(defn- enum-keys
  "Return a sequence of idents in db matching key-ns."
  [db key-ns]
  (->>
   (d/q '[:find [?key ...]
          :in $ ?ns
          :where [_ :db/ident ?i]
                 [(namespace ?i) ?ns]
                 [(name ?i) ?key]]
        db key-ns)
   (map keyword)
   (seq)))

(defn- field-schema
  "Returns a the schema for attributes in datomic-schema format."
  [{ident :db/ident :as attr}]
  (let [enums (and (= (:db/valueType attr) :db.type/ref)
                   (not (:db/isComponent attr))
                   (enum-keys
                    (d/entity-db attr)
                    (str (namespace ident) "." (name ident))))]
    (->> [(symbol (name ident))
          (if enums
            :enum
            (keyword (name (:db/valueType attr))))
          (when enums
            (vec (sort enums)))
          (when-let [u (:db/unique attr)]
            (if (= u :db.unique/identity)
              :unique-identity
              :unique-value))
          (when (:db/index attr)
            :indexed)
          (when (= (:db/cardinality attr) :db.cardinality/many)
            :many)
          (when (:db/fulltext attr)
            :fulltext)
          (when (:db/isComponent attr)
            :component)
          (when (:db/noHistory attr)
            :noHistory)
          (let [doc (:db/doc attr)]
            (when-not (empty? doc)
              doc))]
         (filter identity)
         (vec))))

(defn raw-schema-from-db
  "Return the native datmoic schema, grouped by attribute namespace."
  [db]
  (->> (d/q
        '[:find [?schema-attr ...]
          :where [:db.part/db :db.install/attribute ?schema-attr]]
        db)
       (map (partial d/entity db))
       (group-by (comp namespace :db/ident))))

(defn schema-from-db
  "Return the current db schema in datomic-schema form."
  [db]
  (->> (raw-schema-from-db db)
       (sort-by (fn [[namespace attrs]]
                  ;; Class identifiers can appear out-of-order in
                  ;; auto-generated schema
                  (->> (remove :pace/identifies-class attrs)
                       (map :db/id)
                       (reduce min 100000000))))
       (map
        (fn [[namespace attrs]]
          (list
           'schema (symbol namespace)
           (cons
            'fields
            (doall
             (for [attr (sort-by :db/ident attrs)]
               (field-schema attr)))))))
       (doall)))
