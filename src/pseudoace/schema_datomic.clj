(ns pseudoace.schema-datomic
  (:require [datomic.api :as d]))

(defn thosev
  "Return a vector consisting (only) of the true arguments,
   or `nil` if no arguments are true"
  [& args]
  (vec (filter identity args)))

(defn- enum-keys
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
  [{ident :db/ident :as attr}]
  (let [enums (and (= (:db/valueType attr) :db.type/ref)
                   (not (:db/isComponent attr))
                   (enum-keys
                    (d/entity-db attr)
                    (str (namespace ident) "." (name ident))))]
    (thosev
     (symbol (name ident))

     (if enums
       :enum
       (keyword (name (:db/valueType attr))))

     (if enums
       (vec enums))

     (if-let [u (:db/unique attr)]
       (if (= u :db.unique/identity)
         :unique-identity
         :unique-value))

     (if (:db/index attr)
       :indexed)

     (if (= (:db/cardinality attr) :db.cardinality/many)
       :many)

     (if (:db/fulltext attr)
       :fulltext)

     (if (:db/isComponent attr)
       :component)

     (if (:db/noHistory attr)
       :noHistory)

     (let [doc (:db/doc attr)]
       (if-not (empty? doc) doc)))))

(defn schema-from-db
  "Return the current schema of `db` in datomic-schema form."
  [db]
  (->> (d/q
        '[:find [?schema-attr ...]
          :where [:db.part/db :db.install/attribute ?schema-attr]]
        db)
       (map (partial d/entity db))
       (group-by (comp namespace :db/ident))
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
             (for [attr (sort-by :db/id attrs)]
               (field-schema attr)))))))
       (doall)))
