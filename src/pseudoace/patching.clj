(ns pseudoace.patching
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clj-time.core :as ct]
   [datoteka.core :as fs]
   [datoteka.storages :as dst]
   [lambdaisland.uri :refer [join uri map->URI]]
   [miner.ftp :as ftp]
   [pseudoace.aceparser :as ace]
   [pseudoace.ts-import :as ts-import]
   [pseudoace.utils :as utils]))

(defn find-release-code [url]
  (re-find #"WS\d+" url))

(defn rename-with-ext [filename ext]
  (let [full-ext (str/join "" (drop-while #(not (str/includes? % ".")) filename))]
    (str/replace filename full-ext (str "." ext))))

(def list-ace-files (filter #(or (= (fs/ext %) "ace")
                                 (str/ends-with? % ".ace.gz"))))

(defn santise-lookup-ref [datom]
  (let [op (first datom)]
    (cond
      (and (keyword? op) (= (name op) "retractEntity"))
      (if (vector? (second datom))
        [op (->> datom
                 (second)
                 (take 2)
                 (vec))]
        datom)
      :default
      datom)))

(defn ace-patch-to-edn [imp db patch-file]
  (try
    (->> (utils/read-ace patch-file)
         (ts-import/patches->log imp db)
         (mapcat val)
         (map santise-lookup-ref)
         (vec))
    (catch Exception ex
      (throw (ex-info (.getMessage ex)
                      (merge {:path (str patch-file)}
                             (ex-data ex)))))))

(defn fetch-ace-patches
  "Fetch ACe patches from the FTP URL given.

  Patches are actually pulled with HTTP to workaround a bug in miner.ftp
  Returns a string specifing the local directory where patches are downloaded."
  [patches-ftp-url verbose]
  (let [ftp-url (-> (uri patches-ftp-url)
                    (assoc :user "anonymous" :password "wormbase-at-EBI")
                    (str))
        ftp-files (ftp/list-files ftp-url)
        patch-filenames (sequence list-ace-files ftp-files)
        release-code (some->> (str/split ftp-url #"/")
                              (take-last 3)
                              (first))]
    (when-not release-code
      (throw (ex-info "Unable to determin WS release code from FTP url provided, aborting."
                      {:ftp-url patches-ftp-url})))
    (let [patches-dir (fs/create-tempdir (str "patches-" release-code "-"))]
      (when (seq patch-filenames)
        (doseq [pfn patch-filenames]
          (let [local-path (str (fs/path patches-dir pfn))
                file-type (if (= (fs/ext local-path) "gz")
                            :binary
                            :ascii)]
            (ftp/with-ftp [client ftp-url
                           :file-type file-type]
              (ftp/client-get client pfn local-path)))))
        patches-dir)))

(defn convert-patch [imp db ace-patch-path patches-path]
  (let [edn-filename (rename-with-ext (fs/name ace-patch-path) "edn")
        edn-path (fs/join patches-path edn-filename)
        items (ace-patch-to-edn imp db ace-patch-path)
        fabricate-ace-timestamp (fn []
                                  (-> (ct/now)
                                      (str)
                                      (str/replace "T" "_")
                                      (str/replace "Z" "_datomic_patch")))]
    (if (seq items)
      (do
        (utils/with-outfile edn-path
          (doseq [item items]
            (println (fabricate-ace-timestamp) (pr-str item))))
        (fs/file edn-path))
      (println "WARNING: No EDN transactions generated for patch file"
               {:patch-file ace-patch-path}))))
