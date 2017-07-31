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

(def ^{:private true} report-headings
  ["ACeDB" "datomic" "Missing" "Added" "Identical"])

(defn- parse-csv-line [line-number line]
  (try
    (csv/parse-csv line)
    (catch Exception e
      (ex-info "Failed to parse CSV line"
               {:line-number line-number
                :line line}))))

(defn- write-append [writer record
                     & {:keys [verbose]
                        :or {verbose false}}]
  (let [s (csv/write-csv [record])]
    (when verbose
      (print s))
    (.write writer s)))

(defn- merge-split-record [rec]
  (list (first rec)
        (->> (rest rec)
             (str/join ",")
             (str/trim))))

(defn read-ref-data
  "Read class data generated from a WormBase ACeDB database via `reader`.

  The file contains one line per class-value pair, in the format:
     className : identifier

  Returns a mapping of className to set of identifiers per ACeDB class."
  [acedb-report-path]
  (with-open [rdr (io/reader acedb-report-path)]
    (->> (line-seq rdr)
         (map-indexed parse-csv-line)
         (map first)
         (map merge-split-record)
	 (merge-pairs))))

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

(defn db-data [db attr]
  (->> (d/q '[:find [?v ...]
             :in $ ?a
             :where [_ ?a ?v]]
            db attr)
       (map pr-str)
       set))

(defn- report-entry [db ref-data native->ref attr]
  (let [class-name (native->ref attr)
        db-values (db-data db attr)
        ref-values (set (ref-data class-name))
        [ref-only db-only in-both] (diff ref-values db-values)]
    (->StatsReportEntry class-name attr db-only ref-only in-both)))

(defn- stats-data [report]
  (let [attr-ns (comp namespace :attr)
        entries (sort-by attr-ns (:entries report))]
    (for [entry entries]
      (if entry
        (let [class-name (:class-name entry)
              attr-name (attr-ns entry)
              n-ref-only (.n-ref-only entry)
              n-db-only (.n-db-only entry)
              n-both (.n-both entry)]
          (map str [class-name attr-name n-ref-only n-db-only n-both]))))))

(defn report-import-stats
  "Returns a seqeunence of mappings of the diff
   between `:db/ident` values in datomic,
   and ACeDB class IDs found in the file pointed to by `ref-data-path`."
  [db ref-data-path out-path & {:keys [verbose]
                                :or {verbose false}}]
  (let [ref-data (read-ref-data ref-data-path)
        native->ref (into {} (d/q '[:find ?dn ?an
                                    :where
                                    [?e :db/ident ?dn]
                                    [?e :pace/identifies-class ?an]] db))
        attrs (keys native->ref)
        class-names (vals native->ref)]
    (let [rep-entry (partial report-entry db ref-data native->ref)
          report (->StatsReport class-names (pmap rep-entry attrs))]
      (spit out-path "" :append false)
      (with-open [wtr (io/writer out-path :append true)]
        (write-append wtr report-headings :verbose verbose)
        (doseq [record (remove nil? (stats-data report))]
          (write-append wtr record :verbose verbose))))))
