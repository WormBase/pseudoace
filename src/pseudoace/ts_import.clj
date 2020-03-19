(ns pseudoace.ts-import
  (:require
   [clj-time.coerce :refer [from-date to-date]]
   [clj-time.core :as ctc]
   [clj-time.format :as ctf]
   [clojure.instant :refer [read-instant-date]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datomic.api :as d]
   [pseudoace.acedump :refer [ace-date-format]]
   [pseudoace.aceparser :as ace]
   [pseudoace.binning :refer [bin]]
   [pseudoace.import :refer [datomize-objval get-tags]]
   [pseudoace.utils :refer [throw-exc
                            conj-if
                            conjv
                            indexed
                            parse-double
                            parse-int
                            strand
                            vmap]])
  (:import
   (java.io FileInputStream FileOutputStream)
   (java.util.zip GZIPInputStream GZIPOutputStream)))

;; Logs are sets of :db/add and :db/retract keyed by ACeDB-style
;; timestamps.
;;
;; The datoms can optionally contain lookup-refs or augmented
;; lookup-refs. These behave as normal lookup-refs if their target
;; already exists in the database.  If not, it should be asserted as
;; part of the first transaction in which it appears.  Lookup refs can
;; optionally contain a third part, which should be the ident of the
;; preferred partition for that entity.  If the importer creates the
;; entity, it will attempt to use this partition.  Partition idents
;; are ignored for entities which already exist.

(def ^{:private true} msg-db-not-provided
  "Can't use named allocation when a db is not provided:")

(def ^{:private true} msg-cannot-link-tmpid
  "Can't link to a non-named tempid")

(def ^:dynamic
  ^{:doc "Don't force :db/txInstant attributes during log replays"}
  *suppress-timestamps* false)

(declare log-nodes)

(def timestamp-pattern
  #"(\d{4}-\d{2}-\d{2})_(\d{2}:\d{2}:\d{2})(?:\.\d+)?_(.*)")

(def pmatch @#'ace/pmatch)

(def assign-alloc-re #"__(ALLOCATE|ASSIGN)__(.+)?")

(defn format-ace-date [date]
  (ctf/unparse ace-date-format date))

(defn- imp-tmp-ident [db alloc-name obj ex-msg]
  (if alloc-name
    (if db
      (str (d/basis-t db) ":" alloc-name)
      (throw (ex-info ex-msg {:obj obj})))
    (str (d/squuid))))

(defn select-ts
  "Return any lines in acedb object `obj` with leading tags matching
  `path`."
  [obj path]
  (for [l (:lines obj)
        :when (pmatch path l)]
    (with-meta
      (nthrest l (count path))
      {:timestamps (nthrest (:timestamps (meta l)) (count path))})))

(defn take-ts
  "Take `n` maps from `seq-of-maps` for sequences with :timestamps
  metadata."
  [n seq-of-maps]
  (with-meta (take n seq-of-maps)
    {:timestamps (take n (:timestamps (meta seq-of-maps)))}))

(defn drop-ts
  "Drop `n` maps from `seq-of-maps` having :timestamps metadata."
  [n seq-of-maps]
  (with-meta (drop n seq-of-maps)
    {:timestamps (drop n (:timestamps (meta seq-of-maps)))}))

(defn- lur
  "Helper to turn 3-element pseudo-lookup-refs into plain lookup-refs."
  [e]
  (if (and (vector? e) (= (count e) 3))
    (vec (take 2 e))
    e))

(defn get-tag-paths [imp nss]
  (->> (mapcat (:tags imp) nss)
       (mapcat
        (fn [attr]
          (let [path (str/split (:pace/tags attr) #" ")]
            (if (> (count path) 1)
              [[path attr] [[(last path)] attr]]
              [[path attr]]))))
       (into {})))

(defn merge-logs
  ([l1 l2]
   (cond
     (nil? l2) l1
     (nil? l1) l2
     :default (reduce
               (fn [m [key vals]]
                 (assoc m key (into (get m key []) vals)))
               l1 l2)))
  ([l1 l2 & ls]
   (reduce merge-logs (merge-logs l1 l2) ls)))

(defn- log-datomize-value [ti db imp value]
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
      (if (= v "now")
        (java.util.Date.)
        (read-instant-date (str/replace v #"_" "T")))
      (if (:pace/fill-default ti)
        (read-instant-date "1977-10-29")))

    :db.type/boolean
    true ; ACeDB just has tag presence/absence rather than booleans.

    :db.type/ref
    (if-let [objref (:pace/obj-ref ti)]
      (if-let [v (first value)]
        (if-let [[_ alloc? alloc-name] (re-matches assign-alloc-re v)]
          (if alloc-name
            [:importer/temp (str (d/basis-t db) ":" alloc-name)]
            (throw-exc "Can't link to a non-named tempid: " v))
          [objref
           v
           (or
            (get-in imp [:classes objref :pace/prefer-part])
            :db.part/user)]))
      (datomize-objval ti imp value))

    ;;default
    (throw-exc "Can't handle " (:db/valueType ti))))

(defn- current-by-concs
  "Index a set of component entities by their concrete values."
  [imp currents concs]
  (reduce
   (fn [cbc ent]
     (assoc cbc
            (mapv (fn [conc]
                    (if-let [obj-ref (:pace/obj-ref conc)]
                      [obj-ref
                       (obj-ref ((:db/ident conc) ent))
                       (get-in imp [:classes obj-ref :pace/prefer-part])]
                      ((:db/ident conc) ent)))
                  concs)
            ent))
   {} currents))

(defn- log-components
  [[_ _ part :as this] current-db current ti imp values]
  (let [single? (not= (:db/cardinality ti) :db.cardinality/many)
        current ((:db/ident ti) current)
        current (or (and current single? [current])
                    current)
        concs (sort-by
               :pace/order
               ((:tags imp)
                (str (namespace (:db/ident ti))
                     "."
                     (name (:db/ident ti)))))
        cbc (current-by-concs imp current concs)
        nss (:pace/use-ns ti)
        ordered? (get nss "ordered")
        hashes (for [ns nss]
                 (d/entity (:db imp) (keyword ns "id")))]
    (reduce
     (fn [log [index lines]]
       (if (and (pos? index) single?)
         (do
           (println
            "WARNING: can't pack into cardinality-one component: "
            this
            lines)
           log)
         (let [cvals (take-ts (count concs) (first lines))
               cdata (map (fn [conc val stamp]
                            [conc
                             (log-datomize-value conc current-db imp [val])
                             stamp])
                          concs
                          cvals
                          (lazy-cat (:timestamps (meta cvals)) (repeat nil)))]
           (if-let [current-comp (cbc (mapv second cdata))]
             ;; Component with these concrete values already exists
             (log-nodes
              (:db/id current-comp)
              current-db
              current-comp
              (map (partial drop-ts (count concs)) lines)
              imp
              nss)

             ;; Otherwise synthesize a component ID and start from scratch
             (let [clean-this (lur this)
                   temp
                   (str/join
                    " "
                    (apply
                     vector
                     clean-this
                     (:db/ident ti)
                     (map (fn [cv]
                            (if-let [[_
                                      alloc?
                                      alloc-name] (re-matches
                                                   assign-alloc-re
                                                   cv)]
                              (str (d/basis-t current-db) ":" alloc-name)
                              cv))
                          cvals)))
                   compid [:importer/temp temp part]]
               (-> (merge-logs
                    ;; concretes
                    (reduce
                     (fn [log [conc lv stamp]]
                       (if lv
                         (update
                          log
                          stamp
                          conj
                          [:db/add compid (:db/ident conc) lv])
                         log))
                     log
                     cdata)

                    ;; hashes
                    (log-nodes
                     compid
                     current-db
                     nil
                     (map (partial drop-ts (count concs)) lines)
                     imp
                     nss))
                   (update
                    (first (:timestamps (meta (first lines))))
                    conj
                    [:db/add this (:db/ident ti) compid])
                   (update
                    (first (:timestamps (meta (first lines))))
                    conj-if
                    (if ordered?
                      [:db/add compid :ordered/index index]))))))))
     {}
     (indexed
      (partition-by (partial take (count concs)) values)))))

(defn- find-keys
  "Helper to group `lines` according to a set of tags.  `tags` should be a
   map of tag-name -> tag-info.  The result is a map with tag-info objects
   as keys."
  [tags lines]
  (reduce
   (fn [m line]
     (loop [[node & nodes]   line
            path             []
            [stamp & stamps] (:timestamps (meta line))]
       ;; Skip deletion nodes, which should be handled elsewhere.
       (if (and node (not= node "-D"))
         (let [path (conj path node)]
           (if-let [ti (tags path)]
             (update-in
              m [ti]
              conjv
              (with-meta
                (or nodes [])
                {:timestamps (or (seq stamps) [stamp])}))
             (recur nodes path stamps)))
         m)))
   {} lines))

(defn- log-nodes [this current-db current lines imp nss]
  (let [tags (get-tag-paths imp nss)]
    (reduce
     (fn [log [ti lines]]
       (if (:db/isComponent ti)
         (merge-logs log
                     (log-components this
                                     current-db
                                     current
                                     ti
                                     imp
                                     lines))
         (reduce
          (fn [log line]
            (if-let [lv (log-datomize-value ti current-db imp line)]
              (update-in
               log
               [(first (:timestamps (meta line)))]
               conj
               [:db/add this (:db/ident ti) lv])
              log))
          log lines)))
     {}
     (find-keys tags lines))))

(defn- get-xref-tags [clent]
  (if-let [xrefs (seq (filter :pace.xref/import (:pace/xref clent)))]
    (->> xrefs
         (mapcat
          (fn [xref]
            (let [path (str/split (:pace.xref/tags xref) #" ")]
              (if (> (count path) 1)
                [[path xref] [[(last path)] xref]]
                [[path xref]]))))
         (into {}))))

(defn log-xref-nodes [this current-db current lines clent imp]
  (if-let [tags (get-xref-tags clent)]
    (reduce
     (fn [log [{obj-ref :pace.xref/obj-ref
                attr :pace.xref/attribute
                :as xref}
               lines]]
       (let [lines (remove empty? lines) ; Ignore empty inbound XREF lines.
             remote-class (d/entity (d/entity-db clent) obj-ref)]
         (if (= (namespace obj-ref) (namespace attr))
           ;; Simple case
           (reduce
            (fn [log line]
              (update
               log
               (first (:timestamps (meta line)))
               conj
               [:db/add
                [obj-ref (first line) (:pace/prefer-part remote-class)]
                attr
                this]))
            log lines)

           ;; Complex case
           (let [[ns an] (str/split (namespace attr) #"\.")
                 link-attr (if an
                             (keyword ns an))
                 link-ent (d/entity (d/entity-db clent) link-attr)]
             (if (and link-ent (= ns (namespace obj-ref)))
               (reduce-kv
                (fn [log xo lines]
                  (let [remote (if-let [[_
                                         alloc?
                                         alloc-name] (re-matches
                                                      assign-alloc-re
                                                      xo)]
                                 (imp-tmp-ident current-db
                                                alloc-name
                                                xo
                                                msg-cannot-link-tmpid)
                                 [obj-ref
                                  xo
                                  (:pace/prefer-part remote-class)])
                        temp (str/join
                              " "
                              [(lur remote)
                               link-attr
                               (if (vector? this)
                                 (second this)
                                 this)])
                        compid [:importer/temp
                                temp
                                (:pace/prefer-part remote-class)]]
                    (merge-logs
                     (update
                      log
                      (first (:timestamps (meta (first lines))))
                      conj
                      [:db/add remote link-attr compid]
                      [:db/add compid attr this])
                     (log-nodes
                      compid
                      current-db
                      nil
                      (map (partial drop-ts 1) lines)
                      imp
                      (:pace.xref/use-ns xref)))))
                log (group-by first lines))

               (do
                 #_(println "WARNING: Couldn't link" attr)
                 log))))))

     {}
     (find-keys tags lines))))

(defn- find-delete-component
  "Attempt to find which component entity is meant in a delete.

  Currently a somewhat-conservative impl."
  [this db imp ti nodes]
  (if-let [current-obj (and db (d/entity db this))]
    (let [single? (not= (:db/cardinality ti) :db.cardinality/many)
          current ((:db/ident ti) current-obj)
          current (or (and current single? [current])
                      current)
          concs   (sort-by
                   :pace/order
                   ((:tags imp)
                    (str (namespace (:db/ident ti)) "." (name (:db/ident ti)))))
          cbc     (current-by-concs imp current concs)
          cvals   (take-ts (count concs) nodes)
          cdata   (map (fn [conc val stamp]
                         [conc
                          (log-datomize-value conc db imp [val])
                          stamp])
                       concs cvals (lazy-cat
                                    (:timestamps (meta cvals))
                                    (repeat nil)))]
      (if-let [current-comp (cbc (mapv second cdata))]
        (:db/id current-comp)))))


(defn log-deletes [this db lines imp nss]
  (let [tags (get-tags imp nss)]  ;; should be changed to use get-tag-paths?
    (reduce
     (fn [log line]
       (loop [[node & nodes]   line
              [stamp & stamps] (:timestamps (meta line))]
         (if node
           (if-let [ti (tags node)]
             (let [retract [:db/retract this (:db/ident ti)]]
               (update
                log
                stamp
                conj-if
                (if (:db/isComponent ti)
                  ;; Need to special-case delete-with-value for
                  ;; components.
                  (if (seq nodes)
                    (if-let [comp (find-delete-component this
                                                         db
                                                         imp
                                                         ti
                                                         nodes)]
                      (conj retract comp))
                    retract)
                  (conj-if
                   retract
                   ;; If no value then this returns nil and we get a
                   ;; "wildcard" retract that will be handled at
                   ;; playback time.
                   (log-datomize-value
                    ti
                    db
                    imp
                    (if nodes
                      (with-meta nodes
                        {:timestamps stamps})))))))))))
     {} lines)))

(defmulti log-custom (fn [obj this imp] (:class obj)))

(defmethod log-custom "LongText" [{:keys [timestamp id text]} _ _]
  {timestamp
   [[:db/add [:longtext/id id] :longtext/text (ace/unescape text)]]})

(defmethod log-custom "DNA" [{:keys [timestamp id text]} _ _]
  {timestamp
   [[:db/add [:dna/id id] :dna/sequence text]]})

(defmethod log-custom "Peptide" [{:keys [timestamp id text]} _ _]
  {timestamp
   [[:db/add [:peptide/id id] :peptide/sequence text]]})

(defn- pair-ts [s]
  (map vector s (:timestamps (meta s))))

(defmethod log-custom "Position_Matrix"
  [{:keys [id timestamp] :as obj} _ _]
  (let [values (->> (select-ts obj ["Site_values"])
                    (map (juxt first (partial drop-ts 1)))
                    (into {}))
        bgs  (->> (select-ts obj ["Background_model"])
                  (map (juxt first (partial drop-ts 1)))
                  (into {}))]
    (->>
     (concat
      (if (seq bgs)
        (let [holder [:importer/temp (str (d/squuid))]]
          (conj
           (for [base ["A" "C" "G" "T"]
                 :let [val (bgs base)]]
             [(first (:timestamps (meta val)))
              [:db/add holder
               (keyword
                "position-matrix.value"
                (.toLowerCase base))
               (parse-double (first val))]])
           [timestamp
            [:db/add [:position-matrix/id id]
             :position-matrix/background holder]])))
      (if (seq values)
        (mapcat
         (fn [index
              [a a-ts]
              [c c-ts]
              [g g-ts]
              [t t-ts]]
           (let [holder [:importer/temp (str (d/squuid))]]
             [[timestamp [:db/add
                          [:position-matrix/id id]
                          :position-matrix/values holder]]
              [timestamp [:db/add holder :ordered/index index]]
              [a-ts
               [:db/add
                holder
                :position-matrix.value/a
                (parse-double a)]]
              [c-ts
               [:db/add
                holder
                :position-matrix.value/c
                (parse-double c)]]
              [g-ts
               [:db/add
                holder
                :position-matrix.value/g
                (parse-double g)]]
              [t-ts
               [:db/add
                holder
                :position-matrix.value/t
                (parse-double t)]]]))
         (iterate inc 0)
         (pair-ts (values "A"))
         (pair-ts (values "C"))
         (pair-ts (values "G"))
         (pair-ts (values "T")))))

     (reduce
      (fn [log [ts datom]]
        (update log ts conjv datom))
      {}))))

(defmethod log-custom "Sequence" [obj this imp]
  (if-let [subseqs (select-ts obj ["Structure" "Subsequence"])]
    (reduce
     (fn [log [subseq start end :as m]]
       ;; WS248 contains ~30 clones with empty Subsequence tags.
       (if (and subseq start end)
         (let [child [:sequence/id subseq :wb.part/sequence]
               start (parse-int start)
               end   (parse-int end)]
           (update
            log
            (first (:timestamps (meta m)))
            conj-if
            [:db/add child :locatable/assembly-parent this]
            [:db/add child :locatable/min (dec start)]
            [:db/add child :locatable/max end]
            [:db/add child :locatable/murmur-bin (bin (second this) (dec start) end)]))))
     {}
     subseqs)))

(defmethod log-custom :default [_ _ _] nil)

(def ^:private s-child-types
  {"Gene_child"            :gene/id
   "CDS_child"             :cds/id
   "Transcript"            :transcript/id
   "Pseudogene"            :pseudogene/id
   "Pseudogene_child"      :pseudogene/id
   "Transposon"            :transposon/id
   "Genomic_non_canonical" :sequence/id
   "Nongenomic"            :sequence/id
   "PCR_product"           :pcr-product/id
   "Operon"                :operon/id
   "AGP_fragment"          :sequence/id
   "Allele"                :variation/id
   "Oligo_set"             :oligo-set/id
   "Feature_object"        :feature/id
   "Feature_data"          :feature-data/id
   "Homol_data"            :homol-data/id
   "Expr_profile"          :expr-profile/id})

(defn- log-children [log sc this imp]
  (reduce
   (fn [log [[type link start end :as m] info-lines]]
     (if-let [ident (s-child-types type)]
       (let [child [ident link (or (get-in imp [:classes ident :pace/prefer-part])
                                   :db.part/user)]
             start (parse-int start)
             end   (parse-int end)
             timestamp (case type
                         "Feature_data"
                         "helper"

                         "Homol_data"
                         "helper"

                         ;; default
                         (second (:timestamps (meta m))))]
         (if (and start end)
           (update
            log
            timestamp
            conj-if
            [:db/add child :locatable/parent this]
            [:db/add child :locatable/min (dec (min start end))]
            [:db/add child :locatable/max (max start end)]
            [:db/add child :locatable/strand (strand start end)]
            (when (= (first this) :sequence/id)
              [:db/add child
               :locatable/murmur-bin
               (bin (second this)
                    (dec (min start end))
                    (max start end))]))
           (update
            log
            timestamp
            conj
            [:db/add child :locatable/parent this])))
       log))
   log
   (group-by (partial take-ts 4) sc)))

(defn log-coreprops [obj this imp]
  (let [children (select-ts obj ["SMap" "S_child"])
        method   (first (select-ts obj ["Method"]))]
    (cond-> nil
      children
      (log-children children this imp)

      method
      (update
       (first (:timestamps (meta method)))
       conj
       [:db/add this :locatable/method [:method/id (first method)]]))))

(defn obj->log
 ([imp obj]
  (obj->log imp nil obj))
 ([imp db {:keys [id] :as obj}]
  (let [ci ((:classes imp) (:class obj))
        [_ alloc? alloc-name] (re-matches assign-alloc-re id)
        part (or (:pace/prefer-part ci) :db.part/user)
        this (if ci
               (if alloc?
                 [:importer/temp
                  (imp-tmp-ident db
                                 alloc-name
                                 id
                                 msg-db-not-provided)
                  part]
                 [(:db/ident ci) (:id obj) part]))]
    (merge-logs
     (if (and this alloc?)
       {nil [[:db/add this (:db/ident ci) :allocate]]})
     (if this
       (cond
        (:delete obj)
        {nil
         [[:db.fn/retractEntity this]]}

        (:rename obj)
        {nil
         [[:db/add this (:db/ident ci) (:rename obj)]]}

        :default
        (merge-logs
         (if-let [ns-ident (:db/ident ci)]
           (log-nodes
            this
            db
            nil   ;; Assume no pre-existing object
            (:lines obj)
            imp
            #{(namespace ns-ident)}))

         (log-xref-nodes
          this
          db
          nil
          (:lines obj)
          ci
          imp)

         (if-let [dels (seq (filter #(= (first %) "-D") (:lines obj)))]
           (log-deletes
            this
            nil
            (map (partial drop-ts 1) dels)  ; Remove the leading "-D"
            imp
            #{(namespace (:db/ident ci))})))))
     (log-coreprops obj this imp)
     (log-custom obj this imp)))))

(defn patch->log [imp db {:keys [id] :as obj}]
  (let [ci ((:classes imp) (:class obj))
        this (if ci  ;; No partition hint, so we can use this as a plain lookup ref.
               [(:db/ident ci) (:id obj)])]
    (if-let [orig (d/entity db this)]
      (cond
       (:delete obj)
       {"patch"
        [[:db.fn/retractEntity this]]}
       (:rename obj)
       {"patch"
        [[:db/add this (:db/ident ci) (:rename obj)]]}
       :default
       (merge-logs
         (if-let [dels (seq (filter #(= (first %) "-D") (:lines obj)))]
           (log-deletes
            this
            db
            (map (partial drop-ts 1) dels) ; Remove the leading "-D"
            imp
            #{(namespace (:db/ident ci))}))
         (log-nodes
          this
          db
          orig
          (:lines obj)
          imp
          #{(namespace (:db/ident ci))})

         (log-xref-nodes
          this
          db
          orig
          (:lines obj)
          ci
          imp)))
      ;; Patch for a non-existant object is equivalent to import.
      (obj->log imp db obj))))

(defn objs->log [imp objs]
  (reduce
   (fn [log obj]
     (if-let [objlog (obj->log imp obj)]
       (merge-logs log objlog)
       log))
   {} objs))

(defn patches->log [imp db objs]
  (reduce
   (fn [log obj]
     (if-let [objlog (patch->log imp db obj)]
       (merge-logs log objlog)
       log))
   {} objs))

(defn- temp-datom [db datom temps index]
  (let [aref (get datom index)]
    (if (vector? aref)
      (let [[k v part] aref
            lref [k v]]
        (if v
          (if (d/entity db lref)
            ;; turn 3-element refs into normal lookup-refs
            [(assoc datom index lref) temps]
            (if-let [tid (temps aref)]
              [(assoc datom index tid) temps]
              (let [tid (d/tempid (or part :db.part/user))]
                [(assoc datom index tid)
                 (assoc temps aref tid)
                 [:db/add tid k v]])))
          (println "Nil in " datom "Lookup ref was :" lref "ref was:" aref)))
      (if aref
        [datom temps]))))

(defn fixup-datoms
  "Replace any lookup refs in `datoms` which can't be resolved in `db`
  with tempids, and expand wildcard :db/retracts.

  This function expands transaction forms such as:
    `[:db/add [:importer/temp tmpid part] ...]`."
  [db datoms]
  (->> (reduce
        (fn [{:keys [done temps] :as last} datom]
          (if-let [[datom temps ex1] (temp-datom db datom temps 1)]
            (if-let [[datom temps ex2] (temp-datom db datom temps 3)]
              {:done  (conj-if done datom ex1 ex2)
               :temps temps}
              last)
            last))
        {:done [] :temps {}}
        (mapcat
         (fn [[op e a v :as datom]]
           (if (and (= op :db/retract)
                    (nil? v))
             (for [[_ _ v] (d/datoms db :eavt (lur e) a)]
               (conj datom v))
             [datom]))
         datoms))
       :done))

(defn- txmeta [stamp]
  (let [[_ ds ts name] (re-matches timestamp-pattern stamp)
        time           (if ds
                         (read-instant-date (str ds "T" ts)))]
    (vmap
     :db/id (d/tempid :db.part/tx)
     :importer/ts-name name
     :db/txInstant (if-not *suppress-timestamps*
                     time))))

(def log-fixups
  {nil (constantly "1977-01-01_01:01:01_nil")
   "original" (constantly "1970-01-02_01:01:01_original")
   "patch" (fn [] (str (format-ace-date (ctc/now)) "patch"))
   "homology" #(str (format-ace-date (ctc/now)) "homology")})

(defn clean-log-keys [log]
  (into {} (for [[k v] log]
             [(if-let [f (log-fixups k)]
                (f)
                k)
              v])))

(defn logs-to-dir
  [logs dir]
  (doseq [[stamp logs] (clean-log-keys logs)
          :let [[_ date time name] (re-matches timestamp-pattern stamp)]]
    ;; filename is date whatever is in 1st column of "edn" file.
    ;; when it's helper.edn.gz, cli.clj moves this to the "helper" sub folder of `dir`
    ;; after conversion.
    (with-open [w (-> (io/file dir (str (or date stamp) ".edn.gz"))
                      (FileOutputStream. true)
                      (GZIPOutputStream.)
                      (io/writer))]
      (binding [*out* w]
        (doseq [l logs]
          (println stamp (pr-str l)))))))

(defn split-logs-to-dir
  "Convert `objs` to log entries then spread them into .edn files
  split by date."
  [imp objs dir]
  (logs-to-dir (objs->log imp objs) dir))

(defn logfile-seq [r]
  (for [l (line-seq r)
        :let [i (.indexOf l " ")]]
    [(.substring l 0 i)
     (read-string (.substring l (inc i)))]))

(defn partition-log
  "Similar to partition-all but understands the log format, and will cut
   after `max-text` chars of string data have been seen."
  [max-count max-text logs]
  (if-let [logs (seq logs)]
    (loop [logs     logs
           accum    []
           text     0]
      (cond
       (or (empty? logs)
           (>= (count accum) max-count)
           (>= text max-text))
       (cons accum (lazy-seq (partition-log max-count max-text logs)))

       :default
       (let [[[_ [_ _ _ v] :as log] & rest] logs]
         (recur rest
                (conj accum log)
                (if (string? v)
                  (+ text (count v))
                  text)))))))

(defn latest-transaction-date
  "Returns the `date-time` of the latest transaction."
  [db]
  (let [bt (-> db d/basis-t d/t->tx)]
    (-> (d/q '[:find ?t
               :in $ ?tx
               :where [?tx :db/txInstant ?t]]
             db bt)
        ffirst
        from-date)))

(defn- ms->s [n]
  (when n
    (/ 1000 n)))

(defn play-logfile
  [con logfile max-count max-text & {:keys [use-with? fixup-datoms?]
                                     :or {use-with? false  ; better name would be `simulate?`
                                          fixup-datoms? true}}]
  (with-open [r (io/reader logfile)]
    (doseq [rblk (partition-log max-count max-text (logfile-seq r))]
      (doseq [sblk (partition-by first rblk)]
        (let [stamp (ffirst sblk)
              blk (map second sblk)
              db (d/db con)
              fdatoms (filter (fn [[_ _ _ v]] (not (map? v))) blk)
              datoms (if fixup-datoms?
                       (fixup-datoms db fdatoms)
                       fdatoms)
              tx-meta (txmeta stamp)
              imp-tx-secs (ms->s (some-> tx-meta :db/txInstant (.getTime)))
              last-db-tx-secs (ms->s (some-> (latest-transaction-date db)
                                             (to-date)
                                             (.getTime)))]
          (if (and (number? stamp)
                   (< imp-tx-secs last-db-tx-secs))
            (println "Skipping transaction with past-date:" stamp)
            (let [has-tx-meta? (seq (dissoc tx-meta :db/id))
                  transact (if use-with?
                             (partial d/with db)
                             (fn [txes]
                               @(d/transact-async con txes)))]
              (let [tx-data (if has-tx-meta?
                              (conj datoms tx-meta)
                              datoms)]
                (try
                  (transact tx-data)
                  (catch Exception ex
                    (throw (ex-info (.getMessage ex)
                                    {:orig-exception ex
                                     :tx-data tx-data}))))))))))))


