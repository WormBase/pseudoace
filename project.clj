(defproject wormbase/pseudoace "0.6.0-SNAPSHOT"
  :description "WormBase database migration library and CLI."
  :source-paths ["src"]
  :resource-paths ["models"]
  :lein-tools-deps/config {:config-files [:install :user :project]}
  :plugins
  [[lein-pprint "1.1.1"]
   [lein-tools-deps "0.4.1"]]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :jvm-opts
  [
   "-XX:-OmitStackTraceInFastThrow"
   ;; Uncomment to prevent missing trace (HotSpot optimisation)
   ;; "-XX:-OmitStackTraceInFastThrow"
   "-XX:+UseG1GC"
   "-XX:MaxGCPauseMillis=50"
   ;; should minimize long pauses.
   "-Ddatomic.objectCacheMax=2500000000"
   "-Ddatomic.txTimeoutMsec=1000000"]
  :main ^:skip-aot pseudoace.cli
  :target-path "target/%s"
  :profiles {:datomic-free
             {:lein-tools-deps/config {:resolve-aliases [:datomic-free]}}
             :datomic-pro
             {:lein-tools-deps/config {:resolve-aliases [:datomic-pro]}}
             :provided
             {:lein-tools-deps/config {:resolve-aliases [:datomic-pro
                                                         :aws-java-sdk-dynamodb]}}
             ;; :dev
             ;; {:aliases
             ;;  {"code-qa"
             ;;   ["do"
             ;;    ["eastwood" "{:exclude-linters [:no-ns-form-found]}"]
             ;;    "test"]}
             ;;  :dependencies [[datomic-schema-grapher "0.0.1"]]
             ;;  :plugins [[com.jakemccrary/lein-test-refresh "0.17.0"]
             ;;            [jonase/eastwood "0.2.3"
             ;;             :exclusions [org.clojure/clojure]]
             ;;            [lein-ancient "0.6.8"]]
             ;;  :resource-paths ["test/resources"]}
             ;; :uberjar [:datomic-free {:aot :all}]
             ;; :test
             ;; [{:resource-paths ["test/resources"]
             ;;   :env {:wb-db-uri "datomic:dev://localhost:4334/WS260"}}]
             }
  :deploy-repositories [["releases" :clojars]])
