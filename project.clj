(defproject wormbase/pseudoace "0.4.15-SNAPSHOT"
  :dependencies [[clj-time "0.13.0"]
                 [clj-yaml "0.4.0"]
                 [datomic-schema "1.3.0"]
                 [environ "1.1.0"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
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
  :profiles {:datomic-free [{:dependencies
                              [[com.datomic/datomic-free "0.9.5554"
                                :exclusions [joda-time]]]}]
             :datomic-pro [{:dependencies
                            [[com.datomic/datomic-pro "0.9.5554"
                              :exclusions [joda-time]]]}]
             :ddb [{:dependencies
                    [[com.amazonaws/aws-java-sdk-dynamodb "1.11.6"
                      :exclusions [joda-time]]]}]
             :dev [:datomic-pro
                   :ddb
                   {:dependencies [[datomic-schema-grapher "0.0.1"]]
                    :plugins [[jonase/eastwood "0.2.3"]
                              [lein-ancient "0.6.8"]
                              [refactor-nrepl "0.2.2"]]
                    :repl {:plugins
                           [[cider/cider-nrepl "0.15.0-SNAPSHOT"]]}
                    :resource-paths ["test/resources"]}]
             :dev-free [:datomic-free :dev]
             :prod [:datomic-pro :ddb]
             :test [{:resource-paths ["test/resources"]
                     :env {:wb-db-uri "datomic:memory://test/WS123"}}]}
  :deploy-repositories [["releases" :clojars]]
  :repositories {"my.datomic.com" {:url "https://my.datomic.com/repo"
                                   :creds :gpg}})
