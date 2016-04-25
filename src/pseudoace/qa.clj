(ns pseudoace.qa
  "Quality Assurance routines."
  (:require
   [clojure.data :refer (diff)]
   [clojure.java.io :as io]
   [clojure.set :refer (union)]
   [clojure.string :as str]
   [datomic.api :as d]
   [pseudoace.import :refer (get-classes)]
   [pseudoace.model2schema :refer (datomize-name)]
   [pseudoace.utils :refer (merge-pairs)]))

(defn write-class-ids
  "Write a file named using `id-set-label` of unique `class-name`
  object `ids` in `directory`."
  [directory class-name ids id-set-label]
  (let [filename (str class-name "-" id-set-label ".dat")
        outfile (io/file directory filename)]
    (with-open [writer (io/writer outfile)]
      (binding [*out* writer]
        (doseq [id (sort ids)]
          (println class-name ":" id))))))

(defn read-ref-data
  "Read class data generated from a WormBase ACeDB database via `reader`.

  The file contains one line per class-value pair, in the format:
     className : identifier

  Returns a mapping of className to set of identifiers per map.
  "
  [reader]
  (with-open [fh reader]
    (let [lines (str/split-lines (slurp fh))
          cls-value-pairs (map #(str/split % #"\s+:\s+") lines)]
      (merge-pairs cls-value-pairs))))

(defrecord ClassStatsReport [class-names entries])

(defprotocol StatsReportEntry
  (n-ref-only [_])
  (n-db-only [_])
  (n-both [_]))

(defrecord ClassStatsReportEntry [class-name db-attr db-only ref-only both]
  StatsReportEntry
  (n-ref-only [this]
    (count (:ref-only this)))
  (n-db-only [this]
    (count (:db-only this)))
  (n-both [this]
    (count (:both this))))

(defn- attr-report [db ref-data native->ref attr]
  (let [class-name (native->ref attr)
        query-result (d/q '[:find ?attr ?name
                            :in $ ?attr
                            :where [_ ?attr ?name]] db attr)
        mapped (or (merge-pairs query-result) {})
        db-values (set (map pr-str (mapped attr)))
        ref-values (ref-data class-name)
        [ref-only db-only in-both] (diff ref-values db-values)]
    (->ClassStatsReportEntry class-name
                             attr
                             db-only
                             ref-only
                             in-both)))

(defn class-by-class-report
  "Returns a seqeunence of mappings of the diff between `db` and `ref-data-path`."
  [db ref-data-path]
  (let [all-class-names (map
                         (comp :pace/identifies-class #(second %))
                         (get-classes db))
        ;; Filter out names that are mapped to a datomic illegal form
        ;; (i.e Those whos names start with an integer, e.g "2-point-data")
        class-names (remove (partial re-find #"^\d+") all-class-names)
        ref-data (read-ref-data (io/reader ref-data-path))
        native-names (map datomize-name class-names)
        attrs (map #(keyword % "id") native-names)
        native->ref (zipmap attrs class-names)
        per-attr-report (partial attr-report db ref-data native->ref)]
    (->ClassStatsReport class-names (pmap per-attr-report attrs))))
