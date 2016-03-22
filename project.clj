(defproject pseudoace "0.0.3-SNAPSHOT"
  :dependencies [[acetyl "0.0.9-SNAPSHOT"]
                 [base64-clj "0.1.1"]
                 [bk/ring-gzip "0.1.1"]
                 [cheshire "5.4.0"]
                 [clj-http "1.1.1"]
                 [clj-time "0.9.0"]
                 [com.amazonaws/aws-java-sdk-dynamodb "1.9.39"
                  :exclusions [joda-time]]
                 [com.andrewmcveigh/cljs-time "0.3.0"]
                 [com.cemerick/friend "0.2.1"]
                 [com.datomic/datomic-pro "0.9.5350"
                  :exclusions [joda-time]]
                 [com.ninjudd/eventual "0.4.1"]
                 [com.ninjudd/ring-async "0.3.1"]
                 [com.novemberain/monger "2.0.0"]
                 [compojure "1.4.0"]
                 [datomic-schema "1.3.0"]
                 [environ "1.0.0"]
                 [fogus/ring-edn "0.2.0"]
                 [friend-oauth2 "0.1.3"]
                 [hiccup "1.0.5"]
                 [org.apache.httpcomponents/httpclient "4.3.6"]
                 [org.clojars.hozumi/clj-commons-exec "1.0.6"]
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/data.csv "0.1.3"]
                 [org.clojure/tools.cli "0.3.1"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/tools.trace "0.7.9"]
                 [org.omcljs/om "0.8.8"]
                 [prismatic/om-tools "0.3.11"]
                 [ring "1.4.0"]
                 [ring/ring-anti-forgery "1.0.0"]
                 [secretary "1.2.3"]]
  :description "ACeDB migration tools"
  :source-paths ["src/clj/pseudoace", "src/clj/"]
  :plugins [[lein-environ "1.0.0"]]
  :javac-options ["-target" "1.6" "-source" "1.6"]
  :license "MIT"
  :repositories [["dasmoth" {:url "http://www.biodalliance.org/people/thomas/repo"}]]
  :jvm-opts ["-Xmx6G"
             ;; same GC options as the transactor,
             "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=50"  
             ;; should minimize long pauses.
             "-Ddatomic.objectCacheMax=2500000000" 
             "-Ddatomic.txTimeoutMsec=1000000"
             ;; Uncomment to prevent missing trace (HotSpot optimisation)
             ;; "-XX:-OmitStackTraceInFastThrow"
             ]
  :main ace-to-datomic)
