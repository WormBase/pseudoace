(ns pseudoace.qa-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer (deftest is)]
   [pseudoace.qa :as qa]))

(def expected-class->counts {"Mass_spec_peptide" 1,
                             "Homology_group" 1,
                             "Motif" 1,
                             "Sequence" 40,
                             "DNA" 24,
                             "Oligo_set" 6,
                             "Oligo" 1,
                             "PCR_product" 2,
                             "TreeNode" 1,
                             "LongText" 22,
                             "Variation" 20,
                             "Gene_name" 2,
                             "Peptide" 9,
                             "Feature" 8,
                             "Accession_number" 21,
                             "Paper" 1,
                             "Expr_pattern" 2,
                             "Picture" 1,
                             "Clone" 5,
                             "Microarray_results" 10,
                             "Gene" 6,
                             "Transcript" 8,
                             "SAGE_tag" 8,
                             "CDS" 10,
                             "Feature_data" 12,
                             "Author" 2,
                             "Interaction" 2,
                             "RNAi" 1,
                             "Homol_data" 10,
                             "Protein" 13})

(deftest test-read-ref-data
  (with-open [rdr (io/reader
                   (io/resource "all_classes_report.WS2000.csv"))]
    (let [build-data (qa/read-ref-data rdr)]
      (is (= (keys build-data) (keys expected-class->counts)))
      (is (= (count (keys build-data)) (count expected-class->counts)))
      (doseq [[cls expected-count] expected-class->counts
              :let [ids (build-data cls)]]
        (is (instance? (type #{}) ids))
        (is (= (count ids) expected-count)
            (str "Testing count of " cls " is " expected-count))))))
