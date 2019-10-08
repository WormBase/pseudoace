(ns pseudoace.cli-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer (deftest is)]
   [pseudoace.cli :as cli]
   [pseudoace.utils :as utils]))

(deftest test-move-helper-log-file
  (when-let [dummy-log-dir (io/file
                            (System/getProperty "java.io.tmpdir")
                            "dummy-edn-log-dir")]
    (.mkdir dummy-log-dir)
    (let [dummy-helper-file (io/file dummy-log-dir "helper.edn.gz")]
      (utils/with-outfile dummy-helper-file
        (println "dummy help edn content"))
      (cli/move-helper-log-file (.getParent dummy-helper-file))
      (let [dummy-log-dir (.getParent dummy-helper-file)
            expected-dest-file (cli/helper-dest-file dummy-log-dir)]
        (is (.exists expected-dest-file))
        (is (not (.exists dummy-helper-file)))
        (io/delete-file expected-dest-file)
        (io/delete-file (.getParent expected-dest-file))))
    (io/delete-file dummy-log-dir)))
