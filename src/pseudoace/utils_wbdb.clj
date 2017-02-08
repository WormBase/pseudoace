(in-ns 'pseudoace.utils)

(require '[clj-yaml.core :as yaml]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[environ.core :as environ])
(import '(java.net URL))

(def ^{:private true
       :doc "The filename of the (yaml) configuration used by
  ElasticBeanstalk. Requires that the `.ebextensions`
  folder is made available as resources."}
  eb-config-filename ".config")

(def ^{:dynamic true
       :doc "The environemnt variable name
  containing the value of the
  main WormBase datomic URI."}
  *wbdb-name-env-key* :wbdb-uri)

(defprotocol WormBaseDatabase
  (-wbdb-name [s] "Returns the short-name of the database."))

(extend-protocol WormBaseDatabase
  nil
  (-wbdb-name [_]
    (if-let [uri (io/resource eb-config-filename)]
      (-wbdb-name uri)
      (throw (IllegalArgumentException.
              "Database URL must be a string or URI (got nil)"))))
  URL
  (-wbdb-name [url]
    (let [eb-opts (->> url
                       (io/reader)
                       (slurp)
                       (yaml/parse-string)
                       :option_settings
                       (map (juxt :option_name :value))
                       (into {}))]
      (if-let [uri (get eb-opts "WB_DB_URI")]
        (-wbdb-name uri))))
  String
  (-wbdb-name [s]
    (when (str/starts-with? s "datomic:")
      (let [parse-from-uri (partial re-matches #"^WS\d{1,5}$")]
        (->> (str/split s #"/")
             (filter parse-from-uri)
             (first)
             (not-empty))))))

(defn wbdb-name
  "Returns the name of the configured WormBase datomic database
   given a URI or string.  When no arguments are supplied, the datomic
   URI will be taken from the environment variable defined by
  `*wbdb-name-env-key*`"
  ([]
   (-wbdb-name (environ/env *wbdb-name-env-key*)))
  ([s]
   (-wbdb-name s)))
