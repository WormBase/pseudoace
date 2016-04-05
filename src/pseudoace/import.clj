(ns pseudoace.import
  (:require [datomic.api :as d :refer (db q entity touch tempid)]
            [clojure.string :as str]
            [clojure.instant :refer (read-instant-date)]
            [pseudoace.aceparser :as ace]
            [pseudoace.utils :refer (conj-in
                                     throw-exc
                                     parse-double
                                     parse-int
                                     vassoc
                                     vmap)])
  (:import java.io.FileInputStream
           java.util.zip.GZIPInputStream))

(defrecord Importer [db classes tags])

(defn get-classes [db]
  (->> (q '[:find ?class ?ci
            :where [?ci :pace/identifies-class ?class]]
          db)
       (mapcat
        (fn [[class ci]]
          (let [e (touch (entity db ci))]
            [[class         e]
             [(:db/ident e) e]])))
       (into {})))

(defn importer [con]
  (let [db     (db con)]
    (Importer.
     db
     (get-classes db)
     (->> (q '[:find ?ai
               :where [?ai :pace/tags _]]
             db)
          (map (fn [[ai]]
                 (touch (entity db ai))))
          (group-by #(namespace (:db/ident %)))))))

(declare import-acenodes)
(declare datomize-value)

(defn get-tags [imp nss]
  (->> (mapcat (:tags imp) nss)
       (map (fn [attr]
              [(last (str/split (:pace/tags attr) #" "))
               attr]))
       (into {})))

(defn get-tagpaths [imp nss]
  (->> (mapcat (:tags imp) nss)
       (map (juxt :pace/tags identity))
       (into {})))

(defn datomize-tags [tags line]
  (if (seq line)
    (if-let [ltt (tags (first line))]
      [ltt (rest line)]
      (recur tags (rest line)))))

(defn datomize-objval [ti imp value]
  (let [ident      (:db/ident ti)
        tags       (get-tagpaths imp #{(str (namespace ident) "." (name ident))
                                       "evidence"})
        ;; Unusually, we want to be greedy here --
        ;; so will iterate backwards through "value"
        maybe-obj  (loop [values value]
                     (if (seq values)
                       (or (tags (str/join " " values))
                           (recur (butlast values)))))]
    (cond
      maybe-obj
      (if (zero? (d/part (:db/id maybe-obj)))
        (throw-exc "Refers to a schema entity: " value)
        (:db/ident maybe-obj))

      :default
      {:db/id    (tempid :db.part/user)
       :db/doc   "confused placeholder!"})))  ; Temp workaround for ?Rearrangement


(defn datomize-value [ti imp value]
  (case (:db/valueType ti)
    :db.type/string
    (or (ace/unescape (first value))
        (if (:pace/fill-default ti) ""))

    :db.type/long
    (parse-int (first value))

    :db.type/float
    (parse-double (first value))

    :db.type/double
    (parse-double (first value))

    :db.type/instant
    (if-let [v (first value)]
      (read-instant-date (str/replace v #"_" "T"))
      (if (:pace/fill-default ti)
        (read-instant-date "1977-10-29")))

    :db.type/boolean
    true  ; ACeDB just has tag presence/absence rather than booleans.

    :db.type/ref
    (if-let [objref (:pace/obj-ref ti)]
      (if (first value)
        {:db/id (tempid :db.part/user)
         objref (first value)})
      (datomize-objval ti imp value))

    ;;default
    (throw-exc "Can't handle " (:db/valueType ti))))

(defn- pace-items-for-ns [imp ns]
  ((:tags imp) ns))

(defn datomize-components [ti imp values]
  (let [concs    (sort-by
                  :pace/order
                  (pace-items-for-ns
                   imp
                   (str (namespace (:db/ident ti)) "." (name (:db/ident ti)))))
        nss      (:pace/use-ns ti)
        ordered? (if nss
                   (nss "ordered"))
        hashes   (for [ns nss]
                   (entity (:db imp) (keyword ns "id")))]

    (map-indexed
     (fn [idx [cvals hlines]]
       (let [comp
             (import-acenodes
              (vassoc
               (if (seq cvals)
                 (into {}
                       (map
                        (fn [conc value]
                          [(:db/ident conc) (datomize-value conc imp [value])])
                        concs cvals))
                 {})
               :ordered/index
               (if ordered?
                 idx))
              (map (partial drop (count concs)) hlines)
              (get-tags imp nss)
              imp)]
         (if (empty? comp)
           {:db/doc "placeholder"}
           comp)))
     (group-by (partial take (count concs)) values))))

(defn import-acenodes [base lines tags imp]
  (reduce
   (fn [ent [ti values]]
     (vassoc
      ent
      (:db/ident ti)
      (if (:db/isComponent ti)
        (let [dc (datomize-components ti imp values)]
          (if (= (:db/cardinality ti) :db.cardinality/one)
            (do
              (if (not= (count dc) 1)
                (println "Expected only one value for " (:db/ident ti)))
              (first dc))
            dc))
        (if (= (:db/cardinality ti) :db.cardinality/one)
          (datomize-value ti imp (first values))
          (seq
           (filter
            identity
            ;; drop nil values.
            (map (partial datomize-value ti imp) values)))))))
   base 
   (reduce (fn [n line]
             (if-let [[ti data] (datomize-tags tags line)]
               (conj-in n ti data)
               n))
           {} lines)))

(defn import-aceobj [obj ci imp]
  (import-acenodes
   {:db/id          (tempid :db.part/user)
    (:db/ident ci)  (:id obj)}
   (:lines obj)
   (get-tags imp #{(namespace (:db/ident ci))})
   imp))

(defmulti import-custom :class)

(defmethod import-custom "Position_Matrix" [obj]
  (let [values (->> (ace/select obj ["Site_values"])
                    (map (juxt first #(mapv parse-double (rest %))))
                    (into {}))
        bgs  (->> (ace/select obj ["Background_model"])
                  (map (juxt first #(parse-double (second %))))
                  (into {}))]
    (vmap
     :position-matrix/values
     (map-indexed
      (fn [idx item]
        (assoc item :ordered/index idx))
      (map
       (fn [a c g t]
         {:position-matrix.value/a a
          :position-matrix.value/c c
          :position-matrix.value/g g
          :position-matrix.value/t t})
       (values "A")
       (values "C")
       (values "G")
       (values "T")))

     :position-matrix/background
     (if (seq bgs)
       {:position-matrix.value/a (bgs "A")
        :position-matrix.value/c (bgs "C")
        :position-matrix.value/g (bgs "G")
        :position-matrix.value/t (bgs "T")}))))

(defmethod import-custom :default [obj]
  {})

(defn import-acefile
  "Read ACeDB objects from r and attempt to convert them to match the
   pseudoace schema in con"
  [f imp]
  (let [classes (get-classes (:db imp))]
    (doall
     (mapcat
      (fn [obj]
        (case (:class obj)
          "LongText"
          [{:db/id            (d/tempid :db.part/user)
            :longtext/id      (:id obj)
            :longtext/text    (ace/unescape (:text obj))}]
          "DNA"
          [{:db/id            (d/tempid :db.part/user)
            :dna/id           (:id obj)
            :dna/sequence     (:text obj)}]

          "Peptide"
          [{:db/id            (d/tempid :db.part/user)
            :peptide/id       (:id obj)
            :peptide/sequence (:text obj)}]

          ;; default
          (when-let [ci (classes (:class obj))]
            [(merge
              (import-aceobj obj ci imp)
              (import-custom obj))])))
      (ace/ace-seq (ace/ace-reader f))))))


(defn do-import
  [con path blocks & {:keys [verbose]
                      :or {verbose true}}]
  (let [imp (importer con)]
    (doseq [b blocks]
      (if verbose
        (println b))
      (let [txd (import-acefile (GZIPInputStream.
                                 (FileInputStream.
                                  (str path b ".ace.gz"))) imp)
            num (count txd)
            bs (int (Math/ceil (/ num 500)))]
        (if bs
          (println "Objects: " num " (" bs ")"))
        (doseq [blk (partition-all bs txd)]
          (let [n (count @(d/transact con blk))]
            (if verbose
              (println "N transctions:" n))))))))
