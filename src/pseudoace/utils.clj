(ns pseudoace.utils
  (:require
   [clj-time.coerce :refer [from-date]]
   [clojure.instant :refer [read-instant-date]]
   [clojure.set :refer [union]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [pseudoace.aceparser :as ace])
  (:import
   (java.util Properties)
   (java.io FileInputStream IOException)
   (java.util.zip GZIPInputStream)))

(def not-nil? (complement nil?))

(defn vmap
  "Construct a map from alternating key-value pairs, discarding any keys
  associated with nil values."
  [& args]
  (into {} (for [[k v] (partition 2 args)
                 :when (not-nil? v)]
             [k v])))

(defn vmap-if
  "Construct a map from alternating key-value pairs, discarding any keys
  associated with nil values.  Return nil if all values are empty"
  [& args]
  (reduce
   (fn [m [k v]]
     (if (nil? v)
       m
       (assoc m k v)))
   nil (partition 2 args)))

(defn vassoc
  "Associate `k`eys with `v`alues in `m`, ignoring any keys associated with
  nil values."
  ([m k v]
     (if v
       (assoc m k v)
       m))
  ([m k v & kvs]
     (when-not (even? (count kvs))
       (throw (IllegalArgumentException. "vassoc expects an even number of arguments after map.")))
     (reduce (fn [m [k v]] (vassoc m k v))
             (vassoc m k v)
             (partition 2 kvs))))

(defn conj-in
  "If `k` is already present in map `m`, conj `v` onto the existing
   seq of values, otherwise assoc a new vector of `v`."
  [m k v]
  (assoc m k (if-let [o (m k)]
               (conj o v)
               [v])))

(defn conj-if
  ([col x]
     (cond
      (nil? x)
      col

      (nil? col)
      [x]

      :default
      (conj col x)))
  ([col x & xs]
     (reduce conj-if (conj-if col x) xs)))

(defn conjv
  "Like `conj` but creates a single-element vector if `coll` is nil."
  ([coll x]
     (if (nil? coll)
       [x]
       (conj coll x)))
  ([coll x & xs]
     (reduce conj (conjv coll x) xs)))

(defmacro throw-exc
  "Concatenate args as if with `str` then throw an exception."
  [& args]
  `(throw (Exception. (str ~@args))))

(defn parse-int [i]
  (when i
    (Integer/parseInt i)))

(defn parse-double [i]
  (when i
    (Double/parseDouble i)))

(defn indexed
  "Returns a lazy sequence of [index, item] pairs, where items come
  from 's' and indexes count up from zero.

  (indexed '(a b c d))  =>  ([0 a] [1 b] [2 c] [3 d])"
  [s]
  (map vector (iterate inc 0) s))

(defn find-index [pred col]
  (loop [[i & is] (range (count col))]
    (if (not (nil? i))
      (if (pred (nth col i))
        i
        (recur is)))))

(defmacro with-outfile
  "Execute `body` with *out* bound to `(writer f)`."
  [f & body]
  (let [fh (gensym)]
    `(with-open [~fh (io/writer ~f)]
       (binding [*out* ~fh]
         ~@body))))

(defn those
  "Return a seq consisting (only) of the true arguments,
   or `nil` if no arguments are true"
  [& args]
  (seq (filter identity args)))

(deftype Pair [k v])

(defn- pair-k [^Pair p]
  (.-k p))

(defn- pair-v [^Pair p]
  (.-v p))

(defn sort-by-cached
  "Similar to `sort-by` but caches the results of `keyfn`."
  [keyfn coll]
  (->> (map #(Pair. (keyfn %) %) coll)
       (into-array Pair)
       (sort-by pair-k)
       (mapv pair-v)))

;;
;; From old clojure-contrib
;;

(defmacro cond-let
  "Takes a binding-form and a set of test/expr pairs. Evaluates each test
  one at a time. If a test returns logical true, cond-let evaluates and
  returns expr with binding-form bound to the value of test and doesn't
  evaluate any of the other tests or exprs. To provide a default value
  either provide a literal that evaluates to logical true and is
  binding-compatible with binding-form, or use :else as the test and don't
  refer to any parts of binding-form in the expr. (cond-let binding-form)
  returns nil."
  [bindings & clauses]
  (let [binding (first bindings)]
    (when-let [[test expr & more] clauses]
      (if (= test :else)
        expr
        `(if-let [~binding ~test]
           ~expr
           (cond-let ~bindings ~@more))))))

(defn merge-pairs
  "Merge a sequence of pairs in `pairs`.

  Optionally, specify the `keyfunc` to merge each the pair
  in the seqeunce. By default this is `first`.

  Returns a map of sets."
  [pairs & {:keys [keyfunc]
            :or {keyfunc first}}]
  (let [merge-with-set-vals (partial merge-with union)]
    (apply
     merge-with-set-vals
     (map #(hash-map (keyfunc %) (set (rest %))) pairs))))

(defn filter-by-date
  "Filter `coll` by date object `dt` using predicate `pred`."
  ([coll dt pred]
   (filter-by-date coll dt pred #"(\d{4}-\d{2}-\d{2}).*"))
  ([coll dt pred ts-pattern]
   (sort
    (filter
     not-nil?
     (for [item coll]
       (if-let [matches (re-matches ts-pattern item)]
         (let [ts-str (second matches)
               item-dt (-> ts-str read-instant-date from-date)]
           (if (pred item-dt dt)
             item))))))))

(defn package-version
  "Return the version name of a package `pkg-spec`.
  `pkg-spec` should be of the maven form
  `groupId/artifactId` as specified in leiningen dependencies."
  [pkg-spec]
  (let [pom-props-path (str "META-INF/maven/"
                            pkg-spec
                            "/pom.properties")]
    (if-let [resrc (io/resource pom-props-path)]
      (->> (doto (Properties.)
             (.load (io/reader resrc)))
           (into {})
           (walk/keywordize-keys)
           :version)
      (let [artefact-id (last (str/split pkg-spec #"/"))
            lein-spec (str artefact-id ".version")]
        (System/getProperty lein-spec)))))

(defn distinct-by
  "Returns a lazy sequence of the elements of coll, removing any elements that
  return duplicate values when passed to a function f."
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result x]
          (let [fx (f x)]
            (if (contains? @seen fx)
              result
              (do (vswap! seen conj fx)
                  (rf result x)))))))))
  ([f coll]
   (let [step (fn step [xs seen]
                (lazy-seq
                 ((fn [[x :as xs] seen]
                    (when-let [s (seq xs)]
                      (let [fx (f x)]
                        (if (contains? seen fx)
                          (recur (rest s) seen)
                          (cons x (step (rest s) (conj seen fx)))))))
                  xs seen)))]
     (step coll #{}))))

(defn gunzip
  "Helper to decompress a gzip'ed file."
  [f]
  (->> f
       str
       (FileInputStream.)
       (GZIPInputStream.)))

(defn read-ace
  "Read an ACe file, uncompressing via gunzip if neccessary."
  [filename]
  (cond-> filename
    (str/ends-with? filename ".ace.gz") (gunzip)
    :always
    (-> (io/input-stream)
        (ace/ace-reader)
        (ace/ace-seq))))

(defn strand [start end]
  (if (<= start end)
    :locatable.strand/positive
    :locatable.strand/negative))

(defn rm-tree
  "Delete the directory `dir` and all files under `dir` recursively.
  Return true when all files deleted succesfully"
  [dir & [silently]]
  (when (.isDirectory (io/file dir))
    (doseq [f (.listFiles (io/file dir))]
      (rm-tree f silently)))
  (try
    (io/delete-file dir silently)
    (catch IOException ex
      false)))

(load "utils_wbdb")

