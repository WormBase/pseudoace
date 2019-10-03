(ns pseudoace.transcripts-test
  (:require
   [clojure.test :as t]
   [cognitect.transcriptor :as xr]))

(t/deftest test-all-transcripts
  (prn (xr/repl-files "test/pseudoace/transcriptions"))
  (doseq [repl-file (xr/repl-files "test/pseudoace/transcriptions")]
    (xr/run repl-file)))



