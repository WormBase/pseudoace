(ns pseudoace.acedump
  (:require [datomic.api :as d]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [pseudoace.utils :refer (throw-exc)]))

(defrecord Node [type ts value children])

(def ace-date-format
  (tf/formatter "yyyy-MM-dd_hh:mm:ss"))

(def ^{:dynamic true
       :doc "Include timestamps in .ace dumps."}
  *timestamps* true)

(def ^{:dynamic true
       :doc "Include inbound XREFs."}
  *xrefs* false)

(defn tx->ts
  "Generate an ACeDB-style timestamp string from a pseudoace transaction entity map."
  [tx]
  (str
   (tf/unparse ace-date-format (tc/from-date (:db/txInstant tx)))
   "_"
   (or
    (:importer/ts-name tx)
    (:person/name (:wormbase/curator tx))
    "unknown")))

(defn smin
  "Generalized `min` which works on anything that can be `compare`d."
  [a b]
  (if (pos? (compare a b))
    b a))

(defn squote
  "ACeDB-style string quoting"
  [s]
  (str \" (str/replace s "\"" "\\\"") \"))

(defn splice-in-tagpath
  "Splice `vals` onto `root` via a tag-path indicated by a sequence of one or more tag names."
  [root [tag & tags] ts vals]
  (if-let [[ci node] (first (keep-indexed (fn [pos node]
                                            (if (and (= (:type node) :tag)
                                                     (= (:value node) tag))
                                              [pos node]))
                                          (:children root)))]
    (update root :children assoc ci
            (if (seq tags)
              (splice-in-tagpath node tags ts vals)
              (update node :children into vals)))
    (update root :children conj
            (let [node (Node. :tag ts tag [])]
              (if (seq tags)
                (splice-in-tagpath node tags ts vals)
                (update node :children into vals))))))

(declare ace-object)

(defn value-node [db attr ts datom]
  (case (:db/valueType attr)
    :db.type/long
      (Node. :int      ts (:v datom) nil)
    :db.type/float
      (Node. :float    ts (:v datom) nil)
    :db.type/double
      (Node. :float    ts (:v datom) nil)
    :db.type/instant
      (Node. :datetype ts (tf/unparse ace-date-format (tc/from-date (:v datom))) nil)
    :db.type/string
      (Node. :text     ts (:v datom) nil)
    :db.type/ref
      (let [e (d/entity db (:v datom))]
        (or
         (if-let [obj-ref (:pace/obj-ref attr)]
           (Node. (:pace/identifies-class (d/entity db obj-ref))
                  ts
                  (obj-ref e)
                  nil))

         (if-let [tags (:pace/tags e)]
           (Node. :tag ts tags nil))

         (ace-object
          db
          (:v datom)
          (into #{(str
                   (namespace (:db/ident attr))
                   "."
                   (name (:db/ident attr)))}
                (:pace/use-ns attr)))))
    ;; default
    (Node. :text ts "Unknown!" nil)))

(defn- xref-obj
  "Helper to find the object at the outbound end of an inbound XREF.
   Returns a vector of [object attribute comp] where `comp` is the
   component entity which reifies this XREF, or nil for a simple XREF."
  ([db ent obj-ref]
   (xref-obj db ent nil nil obj-ref))
  ([db ent a v obj-ref]
   (or
    (if-let [r (obj-ref ent)]
      [r (d/entity db a) v])
    (if-let [[e a v t] (first (d/datoms db :vaet (:db/id ent)))]
      (xref-obj db (d/entity db e) a v obj-ref)))))

(defn ace-object
 ([db eid]
  (ace-object db eid nil))
 ([db eid restrict-ns]
  (let [datoms        (d/datoms db :eavt eid)
        data          (map (fn [d]
                             [(d/entity db (:a (first d))) d])
                           (partition-by :a datoms))
        data          (if restrict-ns
                        (filter (fn [[a v]]
                                  (restrict-ns (namespace (:db/ident a))))
                                data)
                        data)
        tsmap         (->> (map :tx datoms)
                           (set)
                           (map (fn [tx]
                                  [tx (tx->ts (d/entity db tx))]))
                           (into {}))

        ;; Split positional from tagged attributes
        [class [cid]] (first (filter (comp :pace/identifies-class first) data))
        positional    (filter (comp :pace/order first) data)
        named         (remove (comp :pace/order first) data)

        ;; Make a dummy node carrying tagged attributes
        named-tree    (reduce
                       (fn [root [attr datoms]]
                         (if-let [tags (:pace/tags attr)]
                           (let [min-ts (reduce smin (map (comp tsmap :tx) datoms))]
                             (if (= (:db/valueType attr) :db.type/boolean)
                               (if (some :v datoms)
                                 (splice-in-tagpath
                                  root
                                  (str/split tags #"\s")
                                  min-ts
                                  nil)
                                 root)
                               (splice-in-tagpath
                                root
                                (str/split tags #"\s")
                                min-ts
                                (mapcat (fn [datom]
                                          (let [n (value-node db attr (tsmap (:tx datom)) datom)]
                                            (if (= (:type n) :anonymous)
                                              (:children n)
                                              [n])))
                                        datoms))))
                           root))
                       (Node. :anonymous
                              nil
                              nil
                              [])
                       named)

        ;; Splice in in-bound xrefs, if appropriate.
        named-tree   (if (and *xrefs* class)
                       (reduce
                        (fn [root xref]
                          (if-let [datoms (seq (d/datoms db :vaet eid (:pace.xref/attribute xref)))]
                            (let [obj-ref (:pace.xref/obj-ref xref)
                                  xclass  (:pace/identifies-class (d/entity db obj-ref))
                                  tsmap   (->> (map :tx datoms)
                                               (set)
                                               (map (fn [tx]
                                                      [tx (tx->ts (d/entity db tx))]))
                                               (into {}))
                                  min-ts (reduce smin (map (comp tsmap :tx) datoms))]
                              (splice-in-tagpath
                               root
                               (str/split (:pace.xref/tags xref) #"\s")
                               min-ts
                               (mapcat
                                (fn [datom]
                                  (let [e          (d/entity db (:e datom))
                                        [o a comp] (xref-obj db e obj-ref)
                                        children   (if (and a comp)
                                                     (if-let [hash-ns (:pace.xref/use-ns xref)]
                                                       (:children (ace-object db comp hash-ns))))]
                                    (if o
                                     [(Node. xclass (tsmap (:tx datom)) o children)])))
                                datoms)))
                            root))
                        named-tree
                        (:pace/xref class))
                       named-tree)]
    (cond
     ;; Top level object.  Positional children not allowed.
     class
     (Node. (:pace/identifies-class class)
            (tsmap (:tx cid))
            (:v cid)
            (:children named-tree))

     ;; Positional parameters exist.
     (seq positional)
     (loop [[[attr datoms] & rest] (reverse
                                    (sort-by (comp :pace/order first)
                                             positional))
            children               (:children named-tree)]
       (let [n (assoc (value-node db attr (tsmap (:tx (first datoms))) (first datoms))
                 :children children)]
         (if (seq rest)
           (recur rest [n])
           n)))

     ;; Otherwise return the dummy node -- because its type is :anonymous, its
     ;; children will be spliced.
     :default
     named-tree))))

(defn- flatten-object
  ([root]
     (flatten-object [] root))
  ([prefix root]
     (let [path (conj prefix root)]
       (if-let [c (seq (:children root))]
         (mapcat #(flatten-object path %) c)
         [path]))))

(defn- is-leaf-tag [node]
  (if-let [children (:children node)]
    (and (= (:type node) :tag)
         (not= (:type (first children)) :tag))
    false))

(defn- ace-node-value [node]
  (if (#{:tag :float :int} (:type node))
    (:value node)
    (squote (:value node))))

(defn- ace-node [node]
  (if *timestamps*
    [(ace-node-value node)
     "-O"
     (str \" (:ts node) \")]
    (if (or (is-leaf-tag node) (not= (:type node) :tag))
      [(ace-node-value node)])))

(defn ace-line [toks]
  (str
   (first toks)
   \tab
   (str/join " " (rest toks))))

(defn dump-object
  "Dump an pseudoace entity in .ace format to *out*."
  [root]
  (if *timestamps*
    (println (:type root)
             ":"
             (squote (:value root))
             "-O"
             (squote (:ts root)))
    (println (:type root)
             ":"
             (squote (:value root))))
  (doseq [line (flatten-object root)]
    (println (ace-line (mapcat ace-node (rest line)))))
  (println))

(defn dump-class
  "Dump object of class `class` from `db`."
  [db class & {:keys [query delete tag follow format limit]}]
  (if-let [ident (d/q '[:find ?class-ident .
                        :in $ ?class
                        :where
                        [?attr :pace/identifies-class ?class]
                        [?attr :db/ident ?class-ident]]
                   db class)]
    (doseq [id (->> (d/q '[:find [?id ...]
                           :in $ ?ident
                           :where [?id ?ident _]]
                         db ident)
                    (sort)
                    (take (or limit Integer/MAX_VALUE)))]
      (dump-object (ace-object db id)))
    (throw-exc "Couldn't find '" class "'")))

(defn dump-ace-files
  "Dump ace files from a query."
  [db ident obj-name-prefix & {:keys [include-timestamps? out-dir]
                               :or {include-timestamps? false
                                    out-dir "/tmp"}}]
  (let [result (sort-by
                second
                (d/q '[:find ?id ?name
                       :in $ ?ident
                       :where
                       [clojure.string/starts-with? ?name obj-name-prefix]
                       [?id ?ident ?name]]
                     db ident))]
    (doseq [id (map first result)
            :let [obj (ace-object db id)
                  filename (str out-dir (:value obj) ".ace")]]
      (with-open [wrtr (io/writer filename)]
        (binding [*out* wrtr
                  *timestamps* include-timestamps?]
          (dump-object obj))))))
