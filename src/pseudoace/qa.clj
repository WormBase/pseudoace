(ns pseudoace.qa
  "Quality Assurance routines."
  (:require
   [clojure.data :refer [diff]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure-csv.core :as csv]
   [datomic.api :as d]
   [pseudoace.import :refer [get-classes]]
   [pseudoace.model2schema :refer [datomize-name]]
   [pseudoace.utils :refer [merge-pairs]]))

(def ^:private report-headings
  ["ACeDB-class" "datomic-ident" "Missing" "Added" "Identical"])

(defn- write-append [writer record & {:keys [verbose]
                                      :or {verbose false}}]
  (let [s (csv/write-csv [record])]
    (when verbose
      (print s))
    (.write writer s)))

(defn read-ref-data
  "Read class data generated from a WormBase ACeDB database via `reader`.

  The file contains one line per class-value pair, in the format:
     className : identifier

  Returns a mapping of className to set of identifiers per map.
  "
  [acedb-report-path]
  (with-open [rdr (io/reader acedb-report-path)]
    (if-let [records (seq (csv/parse-csv rdr))]
      (merge-pairs records))))

(defrecord StatsReport [class-names entries])

(defprotocol StatsEntry
  (n-ref-only [_])
  (n-db-only [_])
  (n-both [_]))

(defrecord StatsReportEntry [class-name attr db-only ref-only both]
  StatsEntry
  (n-ref-only [this]
    (count (:ref-only this)))
  (n-db-only [this]
    (count (:db-only this)))
  (n-both [this]
    (count (:both this))))

(defn- report-entry [db ref-data native->ref attr]
  (let [class-name (native->ref attr)
        query-result (d/q '[:find ?a ?v
                            :in $ ?a
                            :where [_ ?a ?v]]
                          db attr)
        mapped (or (merge-pairs query-result) {})
        db-values (set (mapped attr))
        ref-values (set (ref-data class-name))
        [ref-only db-only in-both] (diff ref-values db-values)]
    (->StatsReportEntry class-name attr db-only ref-only in-both)))

(defn- stats-data [report]
  (let [entries (sort-by (comp namespace :attr) (:entries report))]
    (for [entry entries
          :let [attr (:attr entry)
                class-name (:class-name entry)]]
      (if entry
        (let [n-ref-only (.n-ref-only entry)
              n-db-only (.n-db-only entry)
              n-both (.n-both entry)]
          (map str [class-name attr n-ref-only n-db-only n-both]))))))

(defn report-import-stats
  "Returns a seqeunence of mappings of the diff
   between `db` and `ref-data-path`."
  [db ref-data-path out-path & {:keys [verbose]
                                :or {verbose false}}]
  (let [class-names (->> (get-classes db)
                         (map (comp :pace/identifies-class #(second %)))
                         set
                         sort)
        native-names (map datomize-name class-names)
        attrs (map #(keyword % "id") native-names)
        native->ref (zipmap attrs class-names)]
    (let [ref-data (read-ref-data ref-data-path)
          rep-entry (partial report-entry db ref-data native->ref)
          report (->StatsReport class-names (pmap rep-entry attrs))]
      (spit out-path "" :append false)
      (with-open [wtr (io/writer out-path :append true)]
        (write-append wtr report-headings :verbose verbose)
        (doseq [record (remove nil? (stats-data report))]
          (write-append wtr record :verbose verbose))))))
