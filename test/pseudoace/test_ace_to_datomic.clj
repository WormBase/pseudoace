(ns pseudoace.test-ace-to-datomic
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer (deftest is)]
   [pseudoace.ace-to-datomic :as a2d]
   [pseudoace.utils :as utils]))


(deftest test-get-current-directory
  (is (.exists (io/file (a2d/get-current-directory)))))

(deftest test-move-helper-log-file
  (when-let [dummy-log-dir (io/file
                            (System/getProperty "java.io.tmpdir")
                            "dummy-edn-log-dir")]
    (.mkdir dummy-log-dir)
    (let [dummy-helper-file (io/file dummy-log-dir a2d/helper-filename)]
      (utils/with-outfile dummy-helper-file
        (println "dummy help edn content"))
      ;; Call function under test
      (a2d/move-helper-log-file (.getParent dummy-helper-file))
      ;; Check expectations
      (let [dummy-log-dir (.getParent dummy-helper-file)
            expected-dest-file (a2d/helper-dest-file dummy-log-dir)]
        (is (.exists expected-dest-file))
        (is (not (.exists dummy-helper-file)))
        (io/delete-file expected-dest-file)
        (io/delete-file (.getParent expected-dest-file))))
    (io/delete-file dummy-log-dir)))