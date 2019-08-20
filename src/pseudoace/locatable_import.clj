(ns pseudoace.locatable-import
  (:require
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

(defn- log-features [feature-lines parent offset]
  (let [offset (or offset 0)]
    (reduce
     (fn [log [[method start-s end-s score note :as core] [lines]]]
       (let [tid [:importer/temp (str (d/squuid))]
             start (utils/parse-int start-s)
             end (utils/parse-int end-s)
             min (+ offset -1 (min start end))
             max (+ offset (max start end))]
         (update log (second (:timestamps (meta core)))
                 utils/conj-if
                 [:db/add tid :locatable/parent parent]
                 [:db/add tid :locatable/min min]
                 [:db/add tid :locatable/max max]
                 [:db/add tid :locatable/strand (utils/strand start end)]
                 [:db/add tid :locatable/method [:method/id method]]
                 (if score
                   [:db/add tid :locatable/score (utils/parse-double score)])
                 (if note
                   [:db/add tid :locatable/note note])
                 (bin-me-maybe tid parent min max))))
     {}
     (group-by (partial ts-imp/take-ts 5) feature-lines))))

;; Matt/TODO:: decactivate
(defn- log-splice-confirmations [splice-lines parent offset]
  (let [offset (or offset 0)]
    (reduce
     (fn [log [start-s end-s confirm-type confirm confirm-x :as line]]
       (if confirm
         (let [tid   [:importer/temp (str (d/squuid))]
               start (utils/parse-int start-s)
               end   (utils/parse-int end-s)
               min   (+ offset -1 (min start end))
               max   (+ offset (max start end))]
           (update log (first (drop 2 (:timestamps (meta line))))
             (fn [old]
               (into (or old [])
                     (concat
                      (utils/those
                       [:db/add tid :locatable/parent parent]
                       [:db/add tid :locatable/min min]
                       [:db/add tid :locatable/max max]
                       [:db/add tid :locatable/strand (utils/strand start end)]
                       (bin-me-maybe tid parent min max))

                      (case confirm-type
                        "cDNA"
                        [[:db/add tid :splice-confirm/cdna [:sequence/id confirm]]]

                        "EST"
                        [[:db/add tid :splice-confirm/est [:sequence/id confirm]]]

                        "OST"
                        [[:db/add tid :splice-confirm/ost [:sequence/id confirm]]]

                        "RST"
                        [[:db/add tid :splice-confirm/rst [:sequence/id confirm]]]

                        "RNASeq"
                        (let [rtid [:importer/temp (str (d/squuid))]]
                          [[:db/add tid :splice-confirm/rnaseq rtid]
                           [:db/add rtid :splice-confirm.rnaseq/analysis [:analysis/id confirm]]
                           [:db/add rtid :splice-confirm.rnaseq/count (utils/parse-int confirm-x)]])

                        "Mass_spec"
                        [[:db/add tid :splice-confirm/mass-spec [:mass-spec-peptide/id confirm]]]

                        "Homology"
                        [[:db/add tid :splice-confirm/homology confirm]]

                        "UTR"
                        [[:db/add tid :splice-confirm/utr [:sequence/id confirm]]]

                        "False"
                        [[:db/add tid :splice-confirm/false-splice [:sequence/id confirm]]]

                        "Inconsistent"
                        [[:db/add tid :splice-confirm/inconsistent [:sequence/id confirm]]]))))))
         log))   ;; There are a certain number of these with missing data, which we'll skip.
     {}
     splice-lines)))

;;
;; For version 1, we're *not* going to try collapsing Homol lines into single gapped alignments,
;; since it seems that the blastp importer is only recording one line per sub-hit...
;;
;; this probably needs revisiting if we use this code for things other than ?Protein homols
;;

(defmulti homol-tx-data (fn [ace-tag tx-data tid target protein?]
                          ace-tag))

(defmethod homol-tx-data "Pep_homol" [_ tx-data tid target _]
  (conj tx-data [:db/add tid :homology/protein [:protein/id target]]))

(defmethod homol-tx-data "Motif_homol" [_ tx-data tid target _]
  (conj tx-data [:db/add tid :homology/motif [:motif/id target]]))

(defmethod homol-tx-data "DNA_homol" [_ tx-data tid target protein?]
  (if protein?
    (utils/throw-exc "Don't support protein->DNA homols")
    (conj tx-data [:db/add tid :homology/dna [:sequence/id target]])))

;; Other possible homol tags to dispatch on are (at time of writing):
;; - Structure_homol
;; - RNAi_homol
;; - Oligo_set_homol
;; - Expr_homol
;; - MSPeptide_homol
;; - SAGE_homol
;; - Homol_homol
;; In thomas's original implementation, Homol_homol was silently ignored, the rest generated tx-data.
;; Here we are only chosing to process Pep_homol and Motif_homol as these are the ones needed by the website.
;; Including the rest may lead to excessive database size (for not gain as the data is not used)

(defmethod homol-tx-data :default [_ _ _ _ _]
  nil)

(defn- log-homols [homol-lines parent offset protein?]
  (let [offset (or offset 0)]
    (reduce
     (fn [log [[type target method score-s parent-start-s parent-end-s target-start-s target-end-s :as line] lines]]
       (let [tid [:importer/temp (str (d/squuid))]
             start (utils/parse-int parent-start-s)
             end (utils/parse-int parent-end-s)
             min (if start (+ offset -1 start))
             max (if end (+ offset end))]
         (update log (first (drop 3 (:timestamps (meta line))))
                 (fn [old]
                   (into (or old [])
                         (let [base (utils/those
                                     [:db/add tid :locatable/parent parent]
                                     (and (some? method) [:db/add tid :locatable/method [:method/id method]])
                                     (and (some? min) [:db/add tid :locatable/min min])
                                     (and (some? max) [:db/add tid :locatable/max max])
                                     (and (not-any? nil? [start end]) (if-not protein?
                                                                        [:db/add tid :locatable/strand (utils/strand start end)]))
                                     (and (not-any? nil? [min max]) (bin-me-maybe tid parent min max))
                                     (and (some? target-start-s) [:db/add tid :homology/min (dec (utils/parse-int target-start-s))])
                                     (and (some? target-end-s) [:db/add tid :homology/max (utils/parse-int target-end-s)])
                                     (and (some? score-s) [:db/add tid :locatable/score (utils/parse-double score-s)]))]
                           (if-let [data (homol-tx-data type base tid target protein?)]
                             data
                             (utils/throw-exc "Don't understand homology type " type))))))))
     {}
     (group-by (partial ts-imp/take-ts 8) homol-lines))))

(defmulti log-locatables (fn [_ obj] (:class obj)))

(defmethod log-locatables "Feature_data" [db obj]
  (if-let [fd (d/entity db [:feature-data/id (:id obj)])]
   (let [parent [:sequence/id (:sequence/id (:locatable/parent fd))]]
    (if (= (:locatable/strand fd)
           :locatable.strand/negative)
      (println "Skipping" (:id obj) "because negative strand")
      (ts-imp/merge-logs
       (log-features
        (ts-imp/select-ts obj ["Feature"])
        parent
        (:locatable/min fd))
       (log-splice-confirmations
        (ts-imp/select-ts obj ["Splices" "Confirmed_intron"])
        parent
        (:locatable/min fd))
       ;; Predicted_5 and Predicted_3 don't seem to be used
       )))
    (println "Couldn't find ?Feature_data " (:id obj))))

(defmethod log-locatables "Protein" [db obj]
  (let [parent [:protein/id (:id obj)]]
    (ts-imp/merge-logs
     (log-features (ts-imp/select-ts obj ["Feature"]) parent nil)
     (log-homols   (ts-imp/select-ts obj ["Homol"])   parent nil true))))

(defmethod log-locatables "Homol_data" [db obj]
  (if-let [homol (d/entity db [:homol-data/id (:id obj)])]
   (let [parent [:sequence/id (:sequence/id (:locatable/parent homol))]]
    (if (= (:locatable/strand homol)
           :locatable.strand/negative)
      (println "Skipping" (:id obj) "because negative strand")
      (log-homols
       (ts-imp/select-ts obj ["Homol"])
       parent
       (:locatable/min homol)
       false)))))

(defmethod log-locatables "Sequence" [db obj]
  (let [parent [:sequence/id (:id obj)]]
    (log-splice-confirmations
     (ts-imp/select-ts obj ["Splices" "Confirmed_intron"])
     parent
     nil)))

(defmethod log-locatables :default [_ _]
  nil)

(defn lobjs->log [db objs]
  (reduce ts-imp/merge-logs (map (partial log-locatables db) objs)))

(defn split-locatables-to-dir
  [db objs dir]
  (ts-imp/logs-to-dir (lobjs->log db objs) dir))
