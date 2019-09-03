(ns pseudoace.locatable-import
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [datomic.api :as d]
   [pseudoace.binning :as binning]
   [pseudoace.ts-import :as ts-imp]
   [pseudoace.utils :as utils]))

;;
;; TODO:
;;   - Reinstate strand and missing-parent checks once acedb is fixed.
;;   - More alignment reconstruction?
;;

(defn- bin-me-maybe [this [attr id] min max]
  (if (= attr :sequence/id)
    [:db/add this :locatable/murmur-bin (binning/bin id min max)]))

;
;; For version 1, we're *not* going to try collapsing Homol lines into single gapped alignments,
;; since it seems that the blastp importer is only recording one line per sub-hit...
;;
;; this probably needs revisiting if we use this code for things other than ?Protein homols
;;

;; In thomas's original implementation, Homol_homol was silently ignored, the rest generated tx-data.
;; Here we are only chosing to process Pep_homol and Motif_homol as these are the ones needed by the website.
;; Including the rest may lead to excessive database size (for not gain as the data is not used)
(defn conj-into-tx-data
  [db tx-data tid attr value]
  (conj tx-data [:db/add tid attr value]))

;; Dispatch methods on ACe tag.
;; possible homol tags to dispatch on are (at time of writing):
;; - Structure_homol
;; - RNAi_homol
;; - Oligo_set_homol
;; - Expr_homol
;; - MSPeptide_homol
;; - SAGE_homol
;; - Homol_homol
(defmulti homol-tx-data (fn [db tag tx-data tid value protein?]
                          tag))

(defmethod homol-tx-data "Pep_homol" [db _ tx-data tid value _]
  (conj-into-tx-data db tx-data tid :homology/protein [:protein/id value]))

(defmethod homol-tx-data "Motif_homol" [db _ tx-data tid value _]
  (conj-into-tx-data db tx-data tid :homology/motif [:motif/id value]))

(defmethod homol-tx-data "DNA_homol" [db _ tx-data tid value protein?]
  (if protein?
    (utils/throw-exc "Don't support protein->DNA homols")
    (conj-into-tx-data db tx-data tid :homology/dna [:dna/id value])))

(defmethod homol-tx-data :default [_ _ _ _ _ _]
  nil)

(defn- log-homols [db homol-lines parent offset protein?]
  (let [offset (or offset 0)]
    (reduce
     (fn update-homol [log [[tag
                             target
                             method
                             score-s
                             parent-start-s
                             parent-end-s
                             target-start-s
                             target-end-s :as line] lines]]
       (let [tid (str (d/squuid))
             start (utils/parse-int parent-start-s)
             end (utils/parse-int parent-end-s)
             min (when start
                   (+ offset -1 start))
             max (when end
                   (+ offset end))]
         (update log "homology"
                 (fn [old]
                   (into (or old [])
                         (let [base (utils/those
                                     [:db/add tid :locatable/parent parent]
                                     (when (some? method)
                                       [:db/add tid :locatable/method [:method/id method]])
                                     (when (some? min)
                                       [:db/add tid :locatable/min min])
                                     (when (some? max)
                                       [:db/add tid :locatable/max max])
                                     (when (not-any? nil? [start end])
                                       (if-not protein?
                                         [:db/add tid :locatable/strand (utils/strand start end)]))
                                     (when (not-any? nil? [min max])
                                       (bin-me-maybe tid parent min max))
                                     (when (some? target-start-s)
                                       [:db/add
                                        tid
                                        :homology/min
                                        (dec (utils/parse-int target-start-s))])
                                     (when (some? target-end-s)
                                       [:db/add tid :homology/max (utils/parse-int target-end-s)])
                                     (when (some? score-s)
                                       [:db/add tid :locatable/score (utils/parse-double score-s)]))]
                           (homol-tx-data db tag base tid target protein?))))))))
     {}
     (group-by (partial ts-imp/take-ts 8) homol-lines)))

(defmulti log-locatables (fn [_ obj] (:class obj)))

(defmethod log-locatables "Protein" [db obj]
  (let [parent [:protein/id (:id obj)]]
    (log-homols db (ts-imp/select-ts obj ["Homol"]) parent nil true)))

(defmethod log-locatables :default [_ _]
  nil)

(defn lobjs->log [db objs]
  (reduce ts-imp/merge-logs (map (partial log-locatables db) objs)))

(defn split-locatables-to-dir
  [db objs dir]
  (let [logs (lobjs->log db objs)]
    (ts-imp/logs-to-dir logs dir)))
