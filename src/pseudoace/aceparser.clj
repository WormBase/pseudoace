(ns pseudoace.aceparser
  "Parsing routines for ACeDB 'dump' files."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defrecord AceReader [reader]
  java.io.Closeable
  (close [_]
    (.close reader)))

(defn- ace-line-seq
  "Like line-seq but collapses down any continuation lines in the ace stream."
  [^java.io.BufferedReader rdr]
  (when-let [line (loop [line (.readLine rdr)]
                    (when line
                      (if (.endsWith line "\\")
                        (recur
                         (str (.substring line 0 (dec (count line)))
                              (.readLine rdr)))
                        line)))]
    (cons line (lazy-seq (ace-line-seq rdr)))))

(defn ace-reader
  "Open a .ace file for reading."
  [ace]
  (AceReader. (io/reader ace)))

(defn- null-line?
  [^String line]
  (let [line (.trim line)]
    (or (empty? line)
        (.startsWith line "//"))))

(defn- long-text-end?
  [l]
  (= l "***LongTextEnd***"))

(def ^:private header-re
  #"(?m)^(\w+) *: *(?:\"([^\"]+)\"|(\w+))(?: -O (.*))?")

(def delete-re #"^-D\s+(.+)\s+:\s+(.+)$")

(def rename-re
  #"(?m)^-R\s+(\w+)\s+(\"\"|\"(?:[^\\\"]*|\\.)*\"|\w+)\s(\"\"|\"(?:[^\\\"]*|\\.)*\"|\w+)$")

(def ^:private line-re
  #"(?m)[A-Za-z_0-9:.-]+|\"\"|\"(?:[^\\\"]*|\\.)*\"")

(defn- unquote-str
  [^String s]
  (let [dq "\""]
    (if (.startsWith s dq)
      (if (.endsWith s dq)
        (.substring s 1 (dec (count s)))
        (throw (Exception. (str "malformed string " s))))
      s)))

(defn- drop-timestamp
  [toks]
  (if (= (first toks) "-O")
    (drop 2 toks)
    toks))

(defn- take-timestamp
  [toks]
  (if (= (first toks) "-O")
    (unquote-str (second toks))))

(defn- parse-aceline [line keep-comments?]
  (loop [[t & toks] line
         out        []
         ts         []]
    (cond
      (nil? t)
      (if (some identity ts)
        (with-meta out
          {:timestamps ts})
        out)
      (= t "-C") ; The -C node doesn't have a timestamp
      (if keep-comments?
        (recur toks (conj out t) (conj ts nil))
        (recur (drop-timestamp (drop 1 toks)) out ts))
      :default
      (recur
       (drop-timestamp toks)
       (conj out (unquote-str t))
       (conj ts (take-timestamp toks))))))

(defn- parse-acelines [lines keep-comments?]
  (->> lines
       (map (fn [line]
              (parse-aceline
               (re-seq line-re line)
               keep-comments?)))
       (vec)))

(defn- parse-aceobj
  ([[header-line & lines] keep-comments?]
   (if-let [[_ clazz id] (re-find delete-re header-line)]
     {:class clazz
      :id (unquote-str id)
      :delete true}
     (if-let [[_ clazz id1 id2] (re-find rename-re
                                         (str/replace header-line " : " " "))]
       {:class clazz
        :id (unquote-str id1)
        :rename (unquote-str id2)}
       (if-let [[_ clazz idq idb obj-stamp] (re-find header-re header-line)]
         {:class clazz
          :id (or idq idb)
          :timestamp (if obj-stamp
                       (unquote-str obj-stamp))
          :lines (parse-acelines lines keep-comments?)}
         (throw (Exception. (str "Bad header line: " header-line))))))))

(defn- aceobj-seq
  [lines keep-comments?]
  (lazy-seq
   (let [lines (drop-while null-line? lines)
         header-line (first lines)
         [_ clazz idq idb obj-stamp] (re-find header-re (or header-line ""))]
     (cond
       (empty? header-line)
       nil

       (= clazz "LongText")
       (cons
        {:class "LongText"
         :id (or idq idb)
         :timestamp (if obj-stamp
                      (unquote-str obj-stamp))
         :text (->> (rest lines)
                    (take-while (complement long-text-end?))
                    (str/join \newline)
                    (str/trim))}
        (aceobj-seq
         (rest (drop-while (complement long-text-end?) lines))
         keep-comments?))

       :default
       (let [line-not-empty? (complement null-line?)
             obj-lines (take-while line-not-empty? lines)
             rest-lines (drop-while line-not-empty? lines)]
         (when (seq obj-lines)
           (let [obj (parse-aceobj obj-lines keep-comments?)]
             (case (:class obj)
               "DNA"
               (cons
                (assoc
                 obj
                 :text  (str/join (map first (:lines obj)))
                 :lines nil)
                (aceobj-seq rest-lines keep-comments?))
               "Peptide"
               (cons
                (assoc obj
                       :text  (str/join (map first (:lines obj)))
                       :lines nil)
                (aceobj-seq rest-lines keep-comments?))
               ;; default
               (cons
                obj
                (aceobj-seq rest-lines keep-comments?))))))))))

(defn ace-seq
  "Return a sequence of objects from a .ace file."
  ([ace]
   (ace-seq ace false))
  ([ace keep-comments?]
   (aceobj-seq (ace-line-seq (:reader ace)) keep-comments?)))

(defn- pmatch
  "Test whether `path` is a prefix of `line`."
  [path line]
  (let [path-len (count path)]
    (and (< path-len (count line))
         (= path (take path-len line)))))

(defn select
  "Return any lines in acedb object `obj` with leading tags matching `path`."
  [obj path]
  (for [line (:lines obj) :when (pmatch path line)]
    (nthrest line (count path))))

(defn unescape
  "Unescape double back-slash escaped characters in a string."
  [s]
  (when s
    (str/replace s #"\\(.)" "$1")))

(defn quote-string
  "Apply ACeDB-style string quoting."
  [s]
  (str \" (str/replace s "\"" "\\\"") \"))
