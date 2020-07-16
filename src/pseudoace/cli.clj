(ns pseudoace.cli
  (:require
   [clj-time.core :as ct]
   [clj-time.coerce :refer [to-date]]
   [clojure.java.io :as io]
   [clojure.pprint :as pp :refer [pprint]]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.tools.cli :refer [parse-opts]]
   [datoteka.core :as fs]
   [datomic.api :as d]
   [pseudoace.import :refer [importer]]
   [pseudoace.locatable-import :as loc-import]
   [pseudoace.model :as model]
   [pseudoace.model2schema :as model2schema]
   [pseudoace.patching :as patching]
   [pseudoace.qa :as qa]
   [pseudoace.schema-datomic :as schema-datomic]
   [pseudoace.schemata :as schemata]
   [pseudoace.ts-import :as ts-import]
   [pseudoace.utils :as utils])
  (:import
   (java.util.zip GZIPInputStream)))

(def ^{:dynamic true} *partition-max-count* 1000)

(def ^{:dynamic true} *partition-max-text* 5000)

(def ^{:dynamic true} *homol-db-name* "homol")

(defn exit [status msg]
  (println msg)
  (System/exit status))

;; First three strings describe a short-option, long-option with optional
;; example argument description, and a description. All three are optional
;; and positional.
(def cli-options
  ;; [[ short-opt, long-opt, example], ...]
  [[nil
    "--url URL"
    (str "Datomic database URL; "
         "Example: datomic:free://localhost:4334/WS250")
    :validate [#(re-seq #"datomic:[\-\w]+://[a-zA-Z0-9\.:].+" %)
               "Must be a valid datomic URL."]]
   [nil
    "--schema-filename PATH"
    (str "Name of the file for the schema view "
         "to be written; "
         "Example: \"schema250.edn\"")]
   [nil
    "--log-dir PATH"
    (str "Path to an empty directory to store the Datomic logs in; "
         "Example: /datastore/datomic/tmp/datomic/import-logs-WS250/")]
   [nil
    "--homol-log-dir PATH"
    (str "Path to an empty directory to store the Datomic logs for homology in; "
         "Example: /datastore/datomic/tmp/datomic/WS274-homol-logs/")]
   [nil
    "--acedump-dir PATH"
    (str "Path to the directory of the desired acedump; "
         "Example: /datastore/datomic/tmp/acedata/WS250/")]
   [nil
    "--backup-file PATH"
    "Path to store the database dump."]
   [nil
    "--report-filename PATH"
    (str "Path to the file that you "
         "would like the report to be written to")]
   [nil
    "--acedb-class-report PATH"
    (str "Path to a file containing per class object counts "
         "from ACeDB.")]
   [nil
    "--models-filename PATH"
    (str "Path to the annotated models file")]
   [nil
    "--no-locatable-schema"
    "Supress locatables schema"]
   [nil
    "--no-fixups"
    "Supress schema \"fixups\" (smallace)"]
   [nil
    "--patches-ftp-url URL"
    "URL to the PATCHES directory on an ftp server."]
   [nil
    "--patch-path PATH"
    "Filesystem path to a local ACe patch file."]
   [nil
    "--verify-patch"
    "Verify ACe patch is valid (convertable to EDN AND transactable against the current DB URL."]
   [nil
    "--no-fixup-datoms"
    "Don't try to run the fixup-datoms fn when playing log files."]
   [nil
    "--homol-db-name NAME"
    "Specify an alternate name for the homoolgy database. Defaults to \"homol\"."]
   [nil
    "--reset-log-dir"
    "Remove contents of the log directory specified as --log-dir before processing."]
   ["-v" "--verbose"]
   ["-f" "--force"]
   ["-h" "--help"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn- run-delete-database
  ([url]
   (run-delete-database url false))
  ([url verbose]
   (when verbose
     (println "Deleting database: " url))
   (d/delete-database url)))

(defn- confirm-delete-db! [url]
  (println
   "Are you sure you would like to delete the database: "
   url
   " [y/n]")
  (= (.toLowerCase (read-line)) "y"))

(defn- from-datomic-conn
  [& {:keys [url conn]}]
  (let [conn (or conn (d/connect url))
        db (d/db conn)
        imp (importer conn)]
    [conn db imp]))

(defn delete-database
  "Delete the database at the given URL."
  [& {:keys [url force verbose]
      :or {force false
           verbose false}}]
  (if (or force (confirm-delete-db! url))
    (run-delete-database url verbose)
    (println "Not deleting database")))

(defn generate-schema-view
  "Export the geneated database schema to a file in the \"datomic-schema\" format."
  [& {:keys [url schema-filename verbose]
      :or {verbose false}}]
  (when verbose
    (println "Generating Datomic schema view")
    (println \tab "Creating database connection"))
  (let [conn (d/connect url)
        schemas (schema-datomic/schema-from-db (d/db conn))]
    (utils/with-outfile schema-filename
      (-> schemas vec pprint))
    (when verbose
      (print "Releasing db connection ... "))
    (d/release conn))
  (when verbose
    (println "done.")))

(defn generate-schema
  "Generate the database schema from the annotated ACeDB models."
  [& {:keys [models-filename verbose]
      :or {verbose false}}]
  (when verbose
    (println \tab "Generating Schema")
    (println \tab "Read in annotated ACeDB models")
    (println \tab (str "Making the datomic schema from the acedb "
                       "annotated models")))
  (when-let [stream (-> models-filename
                        io/file
                        io/input-stream)]
    (-> stream
        io/reader
        model/parse-models
        model2schema/models->schema)))

(defn load-schema
  "Load the schema for the database."
  ([url models-filename]
   (load-schema url models-filename false false false))
  ([url models-filename verbose]
   (load-schema url models-filename false false verbose))
  ([url models-filename no-locatables no-fixups verbose]
   (when verbose
     (println (str/join " " ["Loading Schema into:" url]))
     (println \tab "Creating database connection"))
   (let [conn (d/connect url)
         main-schema (generate-schema
                      :models-filename models-filename
                      :verbose verbose)]
     (schemata/install conn
                       main-schema
                       :no-locatables no-locatables
                       :no-fixups no-fixups)
     (d/release conn))))

(defn- db-name-from-url
  "Parse the datomic db name from the URI."
  [url]
  (last (str/split url #"/")))

(defn create-database
  "Create a Datomic database from a schema generated
  and an annotated ACeDB models file."
  [& {:keys [url
             models-filename
             no-locatable-schema
             no-fixups
             verbose]
      :or {no-locatable-schema false
           no-fixups false
           verbose false}}]
  (when verbose
    (println "Creating Database:" (db-name-from-url url))
    (when no-locatable-schema
      (println "Locatable schema will not be applied"))
    (when no-fixups
      (println "Fixups will not be applied")))
  (d/delete-database url)
  (d/create-database url)
  (load-schema url
               models-filename
               no-locatable-schema
               no-fixups
               verbose))

(defn uri-to-helper-uri
  "Create a new URI for the helper db from the main datomic db URI. "
  [uri]
  (str/join "-" [uri "helper"]))

(defn create-helper-database
  "Create a helper Datomic database from a schema."
  [& {:keys [url models-filename verbose]
      :or {verbose false}}]
  (when verbose
    (println "Creating Helper Database with annotated models file:" models-filename))
  (generate-schema
   :models-filename models-filename
   :verbose verbose)
  (let [helper-uri (uri-to-helper-uri url)]
    (println "Helper DB URI:" helper-uri)
    (d/delete-database helper-uri)
    (d/create-database helper-uri)
    (load-schema helper-uri models-filename verbose)))

(defn homol-db-uri
  "Create a URI for the homology db from the main datomic db URI."
  ([url db-name]
   (let [main-db-name (db-name-from-url url)]
     (str/replace url (str  "/" main-db-name) (str "/" db-name))))
  ([url]
   (homol-db-uri url *homol-db-name*)))

(defn create-homol-database
  [& {:keys [url models-filename verbose]
      :or {verbose false}}]
  (println "Homol DB URI:" url)
  (create-database :url url :models-filename models-filename :verbose verbose))

(defn directory-walk [directory pattern]
  (doall (filter #(re-matches pattern (fs/name %))
                 (fs/list-dir directory))))

(defn get-ace-files [directory]
  (map fs/file (directory-walk directory #".*\.ace.gz")))

(defn get-edn-files [log-dir suffix]
  (sort (filter #(str/ends-with? (fs/name %) suffix)
                (fs/list-dir log-dir))))

(defn get-sorted-edn-log-files
  "Sort EDN log files for import.

  Sorting:

    * lexographically by name
    * filter files having names which match a timestamp greater or equal
      to latest transaction date.

  Returns a sequence of sorted files (earliest date first)."
  [log-dir _ latest-tx-dt]
  (let [tx-date (apply
                 ct/date-time
                 (map #(% latest-tx-dt) [ct/year ct/month ct/day]))
        day-before-tx-dt (ct/minus tx-date (ct/days 1))
        edn-files (get-edn-files log-dir ".edn.sort.gz")
        edn-file-map (reduce
                      (fn [m f]
                        (assoc m (fs/name f) f))
                      {}
                      edn-files)
        filenames (utils/filter-by-date
                   (keys edn-file-map)
                   day-before-tx-dt
                   ct/after?)]
  (for [filename filenames]
    (edn-file-map filename))))

(defn apply-patch
  "Apply an ACe patch to the Datomic database specified by url or connection from a local file path."
  [& {:keys [url patch-path verbose verify-patch conn]
      :or {verbose false
           verify-patch false
           conn nil}}]
  (when verbose
    (print "Converting ACe patch" (fs/name patch-path) "to EDN ... "))
  (let [[conn* db imp] (from-datomic-conn :url url :conn conn)
        patches-dir (fs/parent patch-path)
        edn-patch-file (patching/convert-patch imp db patch-path patches-dir)]
    (when verbose
      (println "done.")
      (if verify-patch
        (println "Verify patch mode is *ON* (no writes will be performed)")
        (print "Applying patch:" (-> edn-patch-file str fs/name) "... ")))
    (try
      (ts-import/play-logfile conn*
                              edn-patch-file
                              *partition-max-count*
                              *partition-max-text*
                              :use-with? verify-patch)
      (catch Exception ex
        (when verify-patch
          (println "ERROR:" patch-path "will fail to be apply due to:"))
        (throw ex))))
    (when verbose
      (println "done.")))

(defn apply-patches
  "Fetch and apply ACeDB patches to the datomic database from a given FTP release URL."
  [& {:keys [url patches-ftp-url verify-patch verbose]
      :or {verbose false
           verify-patch false}}]
  (let [[conn _ _] (from-datomic-conn :url url)
        rc (patching/find-release-code patches-ftp-url)
        patches-path (patching/fetch-ace-patches patches-ftp-url verbose)]
    (if patches-path
      (let [patch-files (some->> (fs/list-dir patches-path)
                                 (remove nil?)
                                 (sequence patching/list-ace-files))]
        (if (seq patch-files)
          (doseq [patch-file patch-files]
            (apply-patch :url url
                         :conn conn
                         :patch-path patch-file
                         :verbose verbose
                         :verify-patch verify-patch))
          (println "NO patch files found in PATCHES directory for" rc)))
      (println (str "No patches detected for " rc " from " patches-ftp-url)))))

(defn acedump-file-to-datalog
  ([imp file log-dir]
   (acedump-file-to-datalog imp file log-dir false))
  ([imp file log-dir verbose]
   (when verbose
     (println \tab "Converting" file))
   ;; then pull out objects from the pipeline in chunks of 20 objects.
   ;; Larger block size may be faster if you have plenty of memory
   (doseq [blk (partition-all 20 (utils/read-ace file))]
     (ts-import/split-logs-to-dir imp blk log-dir))))

(defn helper-dest-file
  [log-dir & {:keys [helper-filename helper-folder-name]
              :or {helper-filename "helper.edn.gz"
                   helper-folder-name "helper"}}]
  (io/file log-dir helper-folder-name helper-filename))

(defn move-helper-log-file
  ([log-dir]
   (move-helper-log-file log-dir false))
  ([log-dir verbose]
   (let [dest-file (helper-dest-file log-dir)
         helper-dir (io/file (.getParent dest-file))]
     (when-not (.exists helper-dir)
       (.mkdir helper-dir))
     (let [source (io/file log-dir (.getName dest-file))]
       (when (.exists source)
         (when verbose
           (println \tab "Moving helper log file"))
         (io/copy source dest-file)
         (io/delete-file source))))))

(defn acedump-to-edn-logs
  "Create the EDN log files."
  [& {:keys [url log-dir acedump-dir verbose]
      :or {verbose false}}]
  (when verbose
    (println "Converting ACeDump to Datomic Log")
    (println "Creating database connection"))
  (let [conn (d/connect url)
        imp (importer conn)
        directory (io/file log-dir)
        files (get-ace-files acedump-dir)]
    (doseq [file files]
      (acedump-file-to-datalog imp file directory verbose))
    (move-helper-log-file log-dir verbose)
    (d/release conn)))

(defn- make-log-importer
  [edn-file-lister-fn]
  (fn [& {:keys [url log-dir partition-max-count partition-max-text no-fixup-datoms verbose]
          :or {partition-max-count *partition-max-count*
               partition-max-text *partition-max-text*
               no-fixup-datoms false
               verbose false}}]
    (when verbose
      (println "Importing logs into datomic database" url "from log files in" log-dir))
    (let [conn (d/connect url)
          db (d/db conn)
          log-files (edn-file-lister-fn log-dir db verbose)]
      (when verbose
        (println "Importing" (count log-files)
                 "log files from" log-dir)
      (doseq [file log-files]
        (when verbose
          (println \tab "importing: " file))
        (ts-import/play-logfile
         conn
         (GZIPInputStream. (io/input-stream file))
         partition-max-count
         partition-max-text
         :fixup-datoms? (not no-fixup-datoms)))
      (d/release conn)))))

(defn import-logs
  [& {:keys [url log-dir partition-max-count partition-max-text no-fixup-datoms verbose]
      :or {partition-max-count *partition-max-count*
           partition-max-text *partition-max-text*
           no-fixup-datoms false
           verbose false}}]
  ((make-log-importer
    (fn [log-dir db verbose]
      (let [latest-tx-dt (ts-import/latest-transaction-date db)]
        (when verbose
          (println "Importing logs with latest tx date: " latest-tx-dt))
        (get-sorted-edn-log-files log-dir db latest-tx-dt))))
   :url url
   :log-dir log-dir
   :partition-max-count partition-max-count
   :partition-max-text partition-max-text
   :no-fixup-datoms no-fixup-datoms
   :verbose verbose))

(defn import-homol-logs
  [& {:keys [url log-dir partition-max-count partition-max-text no-fixup-datoms verbose]
      :or {partition-max-count *partition-max-count*
           partition-max-text *partition-max-text*
           no-fixup-datoms false
           verbose false}}]
  ((make-log-importer
    (fn [log-dir _ _]
      (get-edn-files log-dir ".edn.gz")))
   :url url
   :log-dir log-dir
   :partition-max-count partition-max-count
   :partition-max-text partition-max-text
   :no-fixup-datoms no-fixup-datoms
   :verbose verbose))


(defn excise-tmp-data
  "Remove all the temporary data created during processing."
  [& {:keys [url]}]
  (let [conn (d/connect url)]
    (d/transact
     conn
     [{:db/id (d/tempid :db.part/user)
       :db/excise :importer/temp}])
    (d/request-index conn)
    (->> conn d/db d/basis-t (d/sync-index conn) deref)
    (d/gc-storage conn (to-date (ct/now)))))

(defn run-test-query
  "Perform tests on the generated database."
  [& {:keys [url verbose]
      :or {verbose false}}]
  (let [conn (d/connect url)
        n-expected 1
        datalog-query '[:find ?c
                        :in $
                        :where
                        [?c :gene/id "WBGene00018635"]]
        results (d/q datalog-query (d/db conn))
        n-results (count results)]
    (when verbose
      (println "Testing datomic data, expecting exactly"
               n-expected
               "result")
      (println "Datalog query:" datalog-query)
      (print "Results: ")
      (pprint results))
    (if (t/is (= n-results 1))
      (println "OK")
      (println "Failed to find record matching " datalog-query))
    (d/release conn)))

(defn import-helper-edn-logs
  "Import the helper log files."
  [& {:keys
      [url log-dir partition-max-count partition-max-text verbose]
      :or {partition-max-count *partition-max-count*
           partition-max-text *partition-max-text*
           verbose false}}]
  (when verbose
    (println "Importing helper log into helper database"))
  (let [helper-uri (uri-to-helper-uri url)
        helper-connection (d/connect helper-uri)]
    (println "Helper DB URI:" helper-uri)
    (binding [ts-import/*suppress-timestamps* true]
      (ts-import/play-logfile
       helper-connection
       (-> log-dir helper-dest-file io/input-stream (GZIPInputStream.))
       partition-max-count
       partition-max-text))
    (when verbose
      (println \tab "Releasing helper database connection"))
    (d/release helper-connection)))

(defn helper-file-to-datalog [helper-db file log-dir verbose]
  (when verbose
    (println \tab "Processing locatable data from: " file))
  (doseq [blk (partition-all 20 (utils/read-ace file))]
    (loc-import/split-locatables-to-dir helper-db blk log-dir)))

(def ace-by-cls-dump-pattern #"^dump_\d{4}-\d{2}-\d{2}_A_(\w.+)\.\d+\.ace\.gz")

(def include-homol-ace-classes #{"Protein" "Motif" "Method"})

(defn include-for-homol-import? [filename]
  (some->> filename
           (.getName)
           (re-find ace-by-cls-dump-pattern)
           (last)
           (include-homol-ace-classes)))

(defn get-ace-files-for-homol-import
  [acedump-dir]
  (->> acedump-dir
       (get-ace-files)
       (filter include-for-homol-import?)))

(defn generate-homol-edn-logs
  [& {:keys [url acedump-dir log-dir reset-log-dir verbose]
      :or {reset-log-dir true
           verbose false}}]
  (when reset-log-dir
    (utils/rm-tree log-dir)
    (.mkdir (io/file log-dir)))
  (let [helper-uri (uri-to-helper-uri url)
        helper-connection (d/connect helper-uri)
        helper-db (d/db helper-connection)
        files (get-ace-files-for-homol-import acedump-dir)]
    (doseq [file files]
      (helper-file-to-datalog helper-db file log-dir verbose))
    (when verbose
      (println \tab "Releasing helper database connection"))
    (d/release helper-connection)))

(defn delete-helper-database
  "Delete the \"helper\" database."
  [& {:keys [url verbose]}]
  (when verbose
    (println "Deleting helper database"))
  (let [helper-uri (uri-to-helper-uri url)]
    (println "Deleting DB:" helper-uri)
    (d/delete-database helper-uri)))

(defn prepare-import
  "Setup the database schema and parse the acedb files for later sorting."
  [& {:keys [url
             models-filename
             log-dir
             acedump-dir
             schema-filename
             no-locatable-schema
             no-fixups
             verbose]
      :or {schema-filename nil
           no-locatable-schema false
           no-fixups false
           verbose false}}]
  (create-database :url url
                   :models-filename models-filename
                   :no-locatable-schema no-locatable-schema
                   :no-fixups no-fixups
                   :verbose verbose)
  (acedump-to-edn-logs :url url
                       :log-dir log-dir
                       :acedump-dir acedump-dir
                       :verbose verbose)
  (when schema-filename
    (generate-schema-view
     :url url
     :schema-filename schema-filename
     :verbose verbose)))

(defn list-databases
  "List all databases."
  [& {:keys [url]}]
  (doseq [database-name (d/get-database-names url)]
    (println database-name)))

(defn write-report [filename db]
  (let [elements-attributes (sort (d/q '[:find [?ident ...]
                                         :where [_ :db/ident ?ident]]
                                       db))]
    (with-open [wrtr (io/writer filename)]
      (binding [*out* wrtr]
        (print "element" "\t" "attribute" "\t" "count")
        (doseq [element-attribute elements-attributes]
          (let [element (namespace element-attribute)
                attribute (name element-attribute)
                expression [:find '(count ?eid) '.
                            :where ['?eid element-attribute]]
                entity-name (d/q expression db)
                line (str element "\t" attribute "\t" entity-name)]
            (println line)))))))

(defn generate-report
  "Generate a summary report of database content."
  [& {:keys [url report-filename acedb-class-report verbose]
      :or {verbose false}}]
  (when verbose
    (println "Generating Datomic database report"))
  (let [conn (d/connect url)
        db (d/db conn)]
    (try
      (qa/report-import-stats db acedb-class-report report-filename
                              :verbose verbose)
      (finally
        (d/release conn)))
    nil))

(defn import-homol-refs
  "Copy Protein and Motif identifiers into the homology database."
  [& {:keys [url acedump-dir verbose]
      :or {verbose false}}]
  (let [conn (d/connect url)
        files (get-ace-files-for-homol-import acedump-dir)]
    (when verbose
      (println "Importing stub objections"))
    (doseq [file files]
      (doseq [tx-block (partition-all 500 (utils/read-ace file))]
        (let [tx-data (map (fn [obj]
                             (let [ident-ns (-> obj :class str/lower-case)
                                   ident (keyword ident-ns "id")]
                               {ident (:id obj)}))
                           tx-block)]
          @(d/transact conn tx-data))))))

(defn homol-import
  "Creates a separate database containing homology data.
  URL should be the `main` database URL (e.g datomic:free://localhost:4334/WS274)."
  [& {:keys [url models-filename acedump-dir log-dir homol-log-dir verbose]
      :or {verbose false}}]
  (let [homol-url (homol-db-uri url)]
    (create-helper-database :url url :models-filename models-filename :verbose verbose)
    (import-helper-edn-logs :url url :log-dir log-dir :verbose verbose)
    (create-homol-database :url homol-url :models-filename models-filename :verbose verbose)
    (generate-homol-edn-logs :url url :acedump-dir acedump-dir :log-dir homol-log-dir :verbose verbose)
    (import-homol-refs :url homol-url :acedump-dir acedump-dir :verbose verbose)
    (import-homol-logs :url homol-url :log-dir homol-log-dir :verbose verbose)
    (delete-helper-database :url url :verbose verbose)))

(def cli-actions [#'acedump-to-edn-logs
                  #'apply-patch
                  #'apply-patches
                  #'create-database
                  #'create-helper-database
                  #'create-homol-database
                  #'delete-database
                  #'excise-tmp-data
                  #'generate-report
                  #'generate-schema-view
                  #'import-helper-edn-logs
                  #'import-homol-refs
                  #'import-homol-logs
                  #'import-logs
                  #'list-databases
                  #'prepare-import
                  #'homol-import
                  #'generate-homol-edn-logs
                  #'run-test-query])

(def cli-action-metas (map meta cli-actions))

(def cli-action-map (zipmap
                     (map #(str (:name %)) cli-action-metas)
                     cli-actions))

(def cli-doc-map (into
                  {}
                  (for [m cli-action-metas]
                    {(#(str (:name %)) m) (:doc m)})))

(def ^:private space-join (partial str/join "  "))

(defn- collapse-space
  "Remove occruances of multiple spaces in `s` with a single space."
  [s]
  (str/replace s #"\s{2,}" " "))

(defn- required-kwds
  "Return the required keyword arguments to `func-ref`.

  `func-ref` should be a reference to function."
  [func-ref]
  (let [func-info (meta func-ref)
        arglist (-> func-info :arglists flatten)
        kwds (last arglist)
        opt (apply sorted-set (-> kwds :or keys))
        req (apply sorted-set (:keys kwds))]
    (map keyword (set/difference req opt))))

(defn invoke-action
  "Invoke `action-name` with the options supplied."
  [action options _]
  (let [supplied-opts (set (keys options))
        required-opts (set (required-kwds action))
        missing (set/difference required-opts supplied-opts)]
    (if (empty? missing)
      (apply action (flatten (into '() options)))
      (str
       "Missing required options:"
       (str/join " --" (conj (map name missing) nil))))))

(defn usage
  "Display command usage to the user."
  [options-summary]
  (let [action-names (keys cli-doc-map)
        action-docs (vals cli-doc-map)
        doc-width-left (+ 10 (apply max (map count action-docs)))
        action-width-right (+ 10 (apply max (map count action-names)))
        line-template (str "%-"
                           action-width-right
                           "s%-"
                           doc-width-left
                           "s")]
    (str/join
     \newline
     (concat
      [(str "This is tool for importing data from ACeDB "
            "into a Datomic database")
       ""
       "Usage: pseudoace [options] action"
       ""
       "Options:"
       options-summary
       ""
       (str "Actions: (required options for each action "
            "are provided in square brackets)")]
      (for [action-name (sort action-names)
            :let [doc-string (cli-doc-map action-name)]]
        (let [usage-doc (some-> doc-string
                                str/split-lines
                                space-join
                                collapse-space)]
          (format line-template action-name usage-doc)))))))

(defn -main [& args]
  (let [{:keys [options
                arguments
                errors
                summary]} (parse-opts args cli-options)]
    (when errors
      (exit 1 (error-msg errors)))
    (when (:help options)
      (exit 0 (usage summary)))
    (if-let [action-name (first arguments)]
      (if-let [action (get cli-action-map action-name)]
        (let [error (invoke-action action options (rest arguments))]
          (if (string? error)
            (exit 1 error)
            (exit 0 "OK")))
        (exit 1 (str "Unknown argument(s): " action-name)))
      (exit 0 (usage summary)))))
