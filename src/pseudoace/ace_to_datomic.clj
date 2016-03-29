(ns pseudoace.ace-to-datomic
  (:require [pseudoace.model :as model]
            [pseudoace.model2schema :as model2schema]

            [datomic.api :as datomic]
            [pseudoace.schemata :as schemata]

            [pseudoace.utils :as utils]
            [clojure.pprint :as pp]

            [pseudoace.import :as old-import]
            [pseudoace.ts-import :as ts-import]
            [pseudoace.locatable-import :as loc-import]
            [pseudoace.schema-datomic :as schema-datomic]
            [acetyl.parser :as ace]

            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer (parse-opts)]

            [clojure.test :as t]
            [clojure.java.shell :as shell])
  (:import (java.lang.Runtime)
           (java.lang.Runtimea)
           (java.net InetAddress)
           (java.io.FileInputStream)
           (java.io.File)
           (java.util.zip.GZIPInputStream))
  (:gen-class))

;; First three strings describe a short-option, long-option with optional
;; example argument description, and a description. All three are optional
;; and positional.
(def cli-options
  [[nil
    "--model PATH"
    (str "Specify the model file that you would "
         "like to use that is found in the models folder "
         "e.g. \"models.wrm.WS250.annot\"")]
    [nil
     "--url URL"
     (str "Specify the url of the Dataomic transactor "
          "you would like to connect to. "
          "Example: datomic:free://localhost:4334/WS250")]
   [nil
    "--schema-filename PATH"
    (str "Specify the name of the file for the schema view "
         "to be written to when selecting "
         "Action: dumps the generated generate; "
         "example: \"schema250.edn\"")]
   [nil
    "--log-dir PATH"
    (str "Specifies the path to and empty directory "
         "to store the Datomic logs in. "
         "Example: /datastore/datomic/tmp/datomic/import-logs-WS250/")]
   [nil
    "--acedump-dir PATH"
    (str "Specifies the path to the directory of the desired acedump. "
         "Example /datastore/datomic/tmp/acedata/WS250/")]
   [nil
    "--backup-file PATH"
    (str "Specify the path to the file in which you would like "
         "to have the database dumped into")]
   [nil
    "--datomic-database-report-filename PATH"
    (str "Specify the relative or full path to the file that you "
         "would like the report to be written to")]
   ["-v" "--verbose"]
   ["-f" "--force"]
   ["-h" "--help"]])

(defn usage [options-summary]
  (str/join
   \newline
   [(str "Ace to dataomic is tool for importing data from ACeDB "
         "into to Datomic database")
    ""
    "Usage: ace-to-datomic [options] action"
    ""
    "Options:"
    options-summary
    ""
    (str "Actions: (required options for each action "
         "are provided in square brackets)")
    (str "create-database"
         \tab
         "Create a Datomic database from a schema generated "
         "from an annoted ACeDB models file."
         "Required options [model, url]")
    (str "create--helper-database"
         \tab
         "Create a helper Datomic database from a schema."
         "Required options [model, url]")
    (str "generate-datomic-schema-view"
         \tab
         "Export the geneated database schema to a file. "
         "Required options [schema-filename, url]")
    (str "acedump-to-datomic-log"
         \tab
         "Create the EDN log files."
         "Required options: [url, log-dir, acedump-dir]")
    (str "sort-datomic-log"
         \tab
         "Sort the log files generated from ACeDB dump files."
         "Required options: [log-dir]")
    (str "import-logs-into-datomic"
         \tab
         "Import the sorted EDN log files."
         "Required options: [log-dir, url]")
    (str "import-helper-log-into-datomic"
         \tab
         "Import the helper log files."
         "Required options: [log-dir, url]")
    (str "excise-tmp-data"
         \tab
         "Remove all the temporary data created during processing."
         "Required options: [url]")
    (str "test-datomic-data"
         \tab
         "Perform tests on the generated database."
         "Required options: [url acedump-dir]")
    (str "all-import-actions"
         "Perform all actions required to import data from ACeDB dump files."
         "Required options: [model url schema-filename log-dir acedump-dir]")
    (str "generate-datomic-database-report"
         \tab
         "Generate a summary report of database content."
         "Requried options: [url datomic-database-report-filename]")
    (str "list-databases"
         \tab
         "List all databases."
         "Required options: [url]")
    (str "delete-database"
         "Delete the database at the given URL."
         "Required options: [url].")
    (str "backup-database"
         \tab
         "Backup the database at a given URL to  file.")]))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
    (str/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn run-delete-database [options]
  (datomic/delete-database (:url options))
  (if (:verbose options) (println "Deleting database: " (:url options))))

(defn check-if-delete [url]
  (println
   "Are you sure you would like to delete the database: "
   url
   " [y/n]")
  (.toLowerCase (read-line)))

(defn delete-database [options]
  (if (or (:force options) (= (check-if-delete (:url options)) (str "y")))
    (run-delete-database options)
    (println "Not deleting database")))

(defn generate-datomic-schema-view [options]
  (when (:verbose options)
    (println "Generating Datomic schema view")
    (println "\tCreating database connection"))
  (let [uri (:url options)
        con (datomic/connect uri)]
     (utils/with-outfile (:schema-filename options)
       (doseq [s (schema-datomic/schema-from-db (datomic/db con))]
         (pp/pprint s)
         (println)))
     (if (:verbose options)
         (println "\tReleasing database connection"))
     (datomic/release con)))

(defn generate-schema [options]
  (when (:verbose options)
    (println "\tGenerating Schema")
    (println (str "\\tRead in annotated ACeDB models"
                  "file generated by hand - PAD to create this"))
    (println "\tMaking the datomic schema from the acedb annotated models"))
  (let [path (str/join "" ["models/" (:model options)])
        models (model/parse-models (io/reader path))]
    (model2schema/models->schema models)))

(defn add-schema-attribute-to-datomic-schema [options con schema tx-quiet]
  (if (:verbose options)
    (println "\tAdding extra attribute 'schema' to list of attributes"
             "and add timestamp to preserve ACeDB timeseamps with"
             "auto-generated schema"))
  (try
    (tx-quiet
     con
     (conj schema
           {:db/id          (datomic/tempid :db.part/tx)
            :db/txInstant   #inst "1970-01-01T00:00:01"}))
    (catch Exception e
      (str "Caught Exception: " (.getMessage e)))))

(defn- transact-silenced
  "Tranact the entity `tx` using `con`.
  Suppresses the (potentially-large) report if it succeeds."
  [con tx]
  @(datomic/transact con tx)
  nil)
 
(defn load-schema [uri options]
  (when (:verbose options)
    (println (str/join " " ["Loading Schema into:" uri]))
    (println "\tCreating database connection"))
  (let [con (datomic/connect uri)
        tx-quiet (partial transact-silenced con)]
     (schemata/install tx-quiet (generate-schema options))
     ;; add an extra attribute to the 'schema' list of schema attributes,
     ;; saying this transaction occurs on 1st Jan 1970 to fake a first
     ;; transaction to preserve the acedb timestamps
     (let [schema (generate-schema options)]

       ;; XXX: Work out what this is *actually* doing It looks like
       ;; it's adding the *generated* schema *back* into the db!!!

       ;; Adjust the comment above the let above accordingly.
       (add-schema-attribute-to-datomic-schema options con schema tx-quiet))

     ;; XXX: work out if this additional pace schemata for the
     ;;      locatables can be added with the other pace data, or if
     ;;      it needs doing after like this. Refer to Gary's notes.
     ;;
     ;; pace metadata for locatable schema,
     (if (:verbose options) (println "\tAdding locatables-extras"))
     (tx-quiet schemata/locatable-extras)

     ;; needs to be transacted after the main schema.
     ;; XXX: What is considered that "main" schema in this case?
     ;;      Is it the pace schema or what? ;)
     (if (:verbose options)
       (println "\tAdding wormbase-schema-fixups"))
     (tx-quiet con schemata/fixups)
     (if (:verbose options)
       (println "\tReleasing database connection"))
     (datomic/release con)))

(defn create-database [options]
  (if (:verbose options)
    (println "Creating Database"))
  (generate-schema options)
  (def uri (:url options))
  (datomic/create-database uri)
  (load-schema uri options))

(defn uri-to-helper-uri [uri]
  (str/join "-" [uri "helper"]))

(defn create-helper-database [options]
  (if (:verbose options)
    (println "Creating Helper Database"))
  (generate-schema options)
  (def helper_uri (uri-to-helper-uri (:url options)))
  (datomic/create-database helper_uri)
  (load-schema helper_uri options))

(defn directory-walk [directory pattern]
  (doall (filter #(re-matches pattern (.getName %))
                 (file-seq (io/file directory)))))

(defn get-ace-files [directory]
  (map #(.getPath %) (directory-walk directory #".*\.ace.gz")))

(defn get-datomic-log-files [directory]
  (map #(.getName %) (directory-walk directory #".*\.edn.gz")))

(defn get-datomic-sorted-log-files [log-dir]
  (->> (.listFiles (io/file log-dir))
     (filter #(.endsWith (.getName %) ".edn.sort.gz"))
     (sort-by #(.getName %))))

(def not-nil? (complement nil?))

(defn acedump-file-to-datalog [imp file log-dir verbose]
  (if (not-nil? verbose)  (println "\tConverting " file))
  ;; then pull out objects from the pipeline in chunks of 20 objects.
  ;; Larger block size may be faster if you have plenty of memory
  (doseq [blk (->> (java.io.FileInputStream. file)
                  (java.util.zip.GZIPInputStream.)
                  (ace/ace-reader)
                  (ace/ace-seq)
                  (partition-all 20))]
        (ts-import/split-logs-to-dir imp blk log-dir)))

(defn helper-file [] "helper.edn.gz")

(defn helper-folder [log-dir]
  (str/join "" [log-dir "helper/"]))

(defn helper-dest [log-dir]
  (let [helper_folder (helper-folder log-dir )]
     (str/join "" [(helper-folder log-dir) (helper-file)])))

(defn move-helper-log-file [options]
  (if (:verbose options) (println "\tMoving helper log file"))
  (.mkdir (java.io.File.  (helper-folder (:log-dir options))))
  (let [source (str/join "" [(:log-dir options) "/" (helper-file)])]
     (io/copy (io/file source) (io/file (helper-dest (:log-dir options))))
     (io/delete-file (io/file source))))

(defn acedump-to-datomic-log [options]
  (let [verbose? (:verbose options)]
    (when verbose?
      (println "Converting ACeDump to Datomic Log")
      (println "\tCreating database connection"))
    (let [uri (:url options)
          con (datomic/connect uri)

          ;; Helper object, holds a cache of schema data.
          imp (old-import/importer con)

          ;; Must be an empty directory.
          ;; *Directory path must end in a trailing forward slash, see:
          ;; *
          log-dir (io/file (:log-dir options))

          files (get-ace-files (:acedump-dir options))]
      (doseq [file files]
        (acedump-file-to-datalog imp file log-dir verbose?))

      (move-helper-log-file options)
      (if (:verbose options)(println "\tReleasing database connection"))
      (datomic/release con))))

(defn remove-from-end [s end]
  (if (.endsWith s end)
   (.substring s 0 (- (count s)
                      (count end)))
  s))

(defn check-sh-result [result options]
  (if-not (zero? (:exit result))
     (println "ERROR: Sort command had exit value: " (:exit result) " and err: " (:err result) )
     (if (:verbose options) (println "Sort completed Successfully"))))

(defn get-current-directory []
  (.getCanonicalPath (java.io.File. ".")))

(defn sort-datomic-log-command [file]
  "Invoke the perl helper script to sort EDN files.

Assumes that the current working directory is the top level project
directory."
  (shell/sh "./scripts/sort-edn-log.pl" file :dir (get-current-directory)))

(defn sort-datomic-log [options]
  (if (:verbose options) (println "Sorting datomic log"))
  (let [files (get-datomic-log-files (:log-dir options))]
     (doseq [file files]
         (if (:verbose options) (println "Sorting file " file))
         (let [filepath (str/join "" [(:log-dir options) file])
               result (sort-datomic-log-command filepath)]
             (check-sh-result result options)))))

(defn import-logs-into-datomic [options]
  (if (:verbose options) (println "Importing logs into datomic"))
  (let [uri (:url options)
        con (datomic/connect uri)
        log-files (get-datomic-sorted-log-files (:log-dir options))]
    (if (:verbose options)
      (println "Have " (count log-files) "to import"))
    (doseq [file log-files]
      (if (:verbose options) (println "\timporting: " (.getName file)))
      (ts-import/play-logfile con (java.util.zip.GZIPInputStream. (io/input-stream file))))
    (if (:verbose options) (println "\tReleasing database connection"))
    (datomic/release con)))

(defn excise-tmp-data [options]
  (let [uri (:url options)
        con (datomic/connect uri)]
  (datomic/transact con [{:db/id #db/id[:db.part/user] :db/excise :importer/temp}])))

(defn test-datomic-data [options]
  (if (:verbose options) (println "Testing datomic data"))
  (let [uri (:url options)
        con (datomic/connect uri)
        result_one  (datomic/q '[:find ?c :in $ :where [?c :gene/id "WBGene00018635"]] (datomic/db con))]
     (t/is (= #{[923589767780191]} result_one)) ;; actually got 936783933079204
     (if (:verbose options) (println "\tReleasing database connection"))
     (datomic/release con)))


(defn import-helper-log-into-datomic [options]
  (if (:verbose options) (println "Importing helper log into helper database"))
  (let [helper-uri (uri-to-helper-uri (:url options))
        helper-connection (datomic/connect helper-uri)
        helper-destination (helper-dest (:log-dir options))]
     (binding [ts-import/*suppress-timestamps* true]
         (ts-import/play-logfile helper-connection  (java.util.zip.GZIPInputStream. (io/input-stream helper-destination)) ))
     (if (:verbose options) (println "\tReleasing helper database connection"))
     (datomic/release helper-connection)))

(defn helper-file-to-datalog [helper-db file log-dir verbose]
  (if (not-nil? verbose)
    (println "\tAdding extra data from: " file))
  ;; then pull out objects from the pipeline in chunks of 20 objects.
  (doseq [blk (->> (java.io.FileInputStream. file)
                  (java.util.zip.GZIPInputStream.)
                  (ace/ace-reader)
                  (ace/ace-seq)
                  (partition-all 20))] ;; Larger block size may be faster if
                                       ;; you have plenty of memory.
       (loc-import/split-locatables-to-dir helper-db blk log-dir)))

(defn run-locatables-importer-for-helper [options]
  (if (:verbose options) (println "Importing logs with loactables importer into helper database"))
  (let [helper-uri (uri-to-helper-uri (:url options))
        helper-connection (datomic/connect helper-uri)
        helper-db (datomic/db helper-connection)
        log-dir (:log-dir options)
        files (get-ace-files (:acedump-dir options))]
    (doseq [file files]
      (helper-file-to-datalog helper-db file log-dir (:verbose options)))
    (if (:verbose options)
      (println "\tReleasing helper database connection"))
     (datomic/release helper-connection)))

(defn delete-helper-database [options]
  (if (:verbose options)
    (println "Deleting helper database"))
  (let [helper_uri (uri-to-helper-uri (:url options))]
     (datomic/delete-database helper_uri)))

(defn all-import-actions [options]
   (create-database options)
   (acedump-to-datomic-log options)
;;      (create-helper-database options)
   (generate-datomic-schema-view options)
;;      (import-helper-log-into-datomic options)
;;      (run-locatables-importer-for-helper options)
;;      (delete-helper-database options)
   (sort-datomic-log options)
   (import-logs-into-datomic options)
;;    (excise-tmp-data options)
;;    (test-datomic-data options))
)

(defn list-databases [options]
  (doseq [database-name (datomic/get-database-names (:url options))]
       (println database-name)))

(defn write-datomic-database-report [filename db]
  (let [elements-attributes
        (sort (datomic/q '[:find [?ident ...] :where [_ :db/ident ?ident]] db))]
    (with-open [wrtr (io/writer filename)]
      (binding [*out* wrtr]
        (print "element" "\t" "attribute" "\t" "count")
        (doseq [element-attribute elements-attributes]
          (let [element (namespace element-attribute)
                attribute (name element-attribute)
                expression [:find '(count ?eid) '. :where ['?eid element-attribute]]
                name-of-entity (datomic/q expression db )
                line (str element "\t" attribute "\t" name-of-entity)]
            (println line)))))))

(defn generate-datomic-database-report [options]
  (if (:verbose options)
    (println "Generateing Datomic database report"))
  (let [uri (:url options)
        con (datomic/connect uri)
        db  (datomic/db con)
        filename (:datomic-database-report-filename options)]
     (if (:verbose options) (println "\tGenerating:" filename))
     (write-datomic-database-report filename db)
     (if (:verbose options) (println "\tReleasing database connection"))
     (datomic/release con)))

(defn backup-database [options]
  (if (:verbose options)
    (println "Backing up database"))
  (let [uri (:url options)
        con (datomic/connect uri)]
    (if (:verbose options)
      (println "\tReleasing database connection"))
    (datomic/release con)))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      (not= (count arguments) 2) (exit 1 (usage summary))
      errors (exit 1 (error-msg errors)))
    (case (last arguments)
      "acedump-to-datomic-log"
      (if (or (str/blank? (:url options))
              (str/blank? (:log-dir options))
              (str/blank? (:acedump-dir options)))
        (println "Options url and log-dir and ace-dump-dir"
                 "are required for converting the acedump into datomic-logformat")
        (acedump-to-datomic-log options))

      "create-helper-database"
      (if (or (str/blank? (:model options))
              (str/blank? (:url options)))
        (println "Options url and model are required when creating helper database")
        (create-helper-database options))

      "create-database"
      (if (or (str/blank? (:model options))
              (str/blank? (:url options)))
        (println "Options url and model are required when creating database")
        (create-database options))

      "generate-datomic-schema-view"
      (if (or (str/blank? (:schema-filename options))
              (str/blank? (:url options)))
        (println "Options --url and --schema-filename are required"
                 "for generating the schema view")
        (generate-datomic-schema-view options))

      "import-logs-into-datomic"
      (if (or (str/blank? (:log-dir options))
              (str/blank? (:url options)))
        (println "Options log-dir and url are required"
                 "for importing logs into datomic")
        (import-logs-into-datomic options))

      "import-helper-log-into-datomic"
      (if (or (str/blank? (:log-dir options))
              (str/blank? (:url options)))
        (println "Options log-dir and url are required"
                 "for importing logs into datomic")
        (import-helper-log-into-datomic options))

      "sort-datomic-log"
      (if (str/blank? (:log-dir options))
        (println "Options log-dir is required for sorting the datomic log")
        (sort-datomic-log options))

      "excise-tmp-data"
      (if (str/blank? (:url options))
        (println "Option url is required for removing tmp data")
        (excise-tmp-data options))

      "test-datomic-data"
      (if (str/blank? (:url options))
        (println "Option url is require for performing tests on datomic data")
        (test-datomic-data options))

      "all-import-actions"
      (if (or (str/blank? (:url options))
              (str/blank? (:log-dir options))
              (str/blank? (:acedump-dir options))
              (str/blank? (:schema-filename options))
              (str/blank? (:model options)))
        (println "All options are required if you would like to peform all actions"
                 options)
        (all-import-actions options))

      "delete-database"
      (if (str/blank? (:url options))
        (println "The url option is required for deleting a Datomic database")
        (delete-database options))

      "list-databases"
      (if (str/blank? (:url options))
        (println "The url to the database is required"
                 "to get the list of Dataomic database names")
        (list-databases options))

      "backup-database"
      (if (or (str/blank? (:url options))
              (str/blank? (:backup-file options)))
        (println "The options url and backup-file are required"
                 "for backing up the database")
        (backup-database options))

      "generate-datomic-database-report"
      (if (or (str/blank? (:url options))
              (str/blank? (:datomic-database-report-filename options)))
        (println "The options url and datomic-database-report-filename"
                 " are required for generating the report")
        (generate-datomic-database-report options))

      (exit 1 (usage summary)))))
