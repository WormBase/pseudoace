(defproject wormbase/pseudoace "0.4.13"
  :dependencies [[clj-time "0.12.0"]
                 [datomic-schema "1.3.0"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojure/tools.cli "0.3.5"]]
  :description "ACeDB migration tools"
  :source-paths ["src"]
  :resource-paths ["models"]
  :plugins [[lein-environ "1.0.0"]
            [lein-pprint "1.1.1"]]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :license "GPLv2"
  :jvm-opts ["-Xmx6G"
             ;; same GC options as the transactor,
             "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=50"
             ;; should minimize long pauses.
             "-Ddatomic.objectCacheMax=2500000000"
             "-Ddatomic.txTimeoutMsec=1000000"
             ;; Uncomment to prevent missing trace (HotSpot optimisation)
             ;; "-XX:-OmitStackTraceInFastThrow"
             ]
  :main pseudoace.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :datomic-free [{:dependencies
                              [[com.datomic/datomic-free "0.9.5385"
                                :exclusions [joda-time]]]}]
             :datomic-pro [{:dependencies
                             [[com.datomic/datomic-pro "0.9.5385"
                               :exclusions [joda-time]]]}]
             :test [{:resource-paths ["test/resources"]}]
             :ddb [:datomic-pro
                   {:dependencies
                    [[com.amazonaws/aws-java-sdk-dynamodb "1.11.6"
                      :exclusions [joda-time]]]}]
             :dev [{:dependencies [[datomic-schema-grapher "0.0.1"]]
                    :plugins [[datomic-schema-grapher "0.0.1"]
                              [jonase/eastwood "0.2.3"]
                              [lein-ancient "0.6.8"]
                              [lein-bikeshed "0.3.0"]
                              [lein-kibit "0.1.2"]
                              [lein-ns-dep-graph "0.1.0-SNAPSHOT"]]
                    :resource-paths ["test/resources"]}]
             :dev-ddb [:ddb :dev]
             :dev-free [:datomic-free :dev]
             :dev-mysql [:dev
                         {:dependencies
                          [[mysql/mysql-connector-java "6.0.2"]]}]
             :dev-postgresql [:dev
                              {:dependencies
                               [[org.postgresql/postgresql "9.4.1209"]]}]
             :prod [:ddb]}
  :deploy-repositories [["releases" :clojars]]
  :repositories {"my.datomic.com" {:url "https://my.datomic.com/repo"
                                   :creds :gpg}})
