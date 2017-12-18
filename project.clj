(defproject wormbase/pseudoace "0.5.5-SNAPSHOT"
  :dependencies [[clj-time "0.13.0"]
                 [clj-yaml "0.4.0"]
                 [clojure-csv/clojure-csv "2.0.2"]
                 [com.gfredericks/forty-two "1.0.0"]
                 [datomic-schema "1.3.0"]
                 [environ "1.1.0"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [org.clojure/tools.cli "0.3.5"]]
  :description "WormBase database migration library and CLI."
  :source-paths ["src"]
  :resource-paths ["models"]
  :plugins [[lein-environ "1.0.0"]
            [lein-pprint "1.1.1"]]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :jvm-opts
  [
   ;; Uncomment to prevent missing trace (HotSpot optimisation)
   ;; "-XX:-OmitStackTraceInFastThrow"
   "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=50"
   ;; should minimize long pauses.
   "-Ddatomic.objectCacheMax=2500000000"
   "-Ddatomic.txTimeoutMsec=1000000"]
  :main pseudoace.cli
  :target-path "target/%s"
  :profiles {:datomic-free
             {:dependencies
              [[com.datomic/datomic-free "0.9.5561"
                :exclusions [joda-time]]
               [com.amazonaws/aws-java-sdk-dynamodb "1.11.82"
                :exclusions [joda-time]]]}
             :provided
             {:dependencies
              [[com.datomic/datomic-pro "0.9.5561"
                :exclusions [joda-time]]
               [com.amazonaws/aws-java-sdk-dynamodb "1.11.82"
                :exclusions [joda-time]]]}
             :dev
             {:aliases
              {"code-qa"
               ["do"
                ["eastwood" "{:exclude-linters [:no-ns-form-found]}"]
                "test"]}
              :dependencies [[datomic-schema-grapher "0.0.1"]]
              :plugins [[com.jakemccrary/lein-test-refresh "0.17.0"]
                        [jonase/eastwood "0.2.3"
                         :exclusions [org.clojure/clojure]]
                        [lein-ancient "0.6.8"]]
              :resource-paths ["test/resources"]}
             :uberjar [:datomic-free {:aot :all}]
             :test
             [{:resource-paths ["test/resources"]
               :env {:wb-db-uri "datomic:dev://localhost:4334/WS260"}}]}
  :deploy-repositories [["releases" :clojars]])
