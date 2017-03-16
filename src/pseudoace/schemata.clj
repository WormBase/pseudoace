(ns pseudoace.schemata
  (:require [datomic-schema.schema :refer (generate-schema fields schema)]
            [datomic.api :as d]))

(def earliest-tx-timestamp #inst "1970-01-01T00:00:01")

(defn mark-tx-early
  "Marks mapping `item` with an early timestamp."
  [schema]
  (conj schema
        {:db/id (d/tempid :db.part/tx)
         :db/txInstant earliest-tx-timestamp}))

(defn conj-install-part
  "Marks a mapping `item` to be an installable datomic structure."
  [item]
  (conj item {:db/id (d/tempid :db.part/db)
              :db.install/_attribute :db.part/db}))

(def meta-schema
  (map
   conj-install-part
   [{:db/ident        :pace/identifies-class
     :db/valueType    :db.type/string
     :db/cardinality  :db.cardinality/one
     :db/unique       :db.unique/identity
     :db/doc          (str "Attribute of object-identifers "
                           "(e.g. :gene/id), indicating the name of the "
                           "corresponding ACeDB class.")}

    {:db/ident        :pace/is-hash
     :db/valueType    :db.type/boolean
     :db/cardinality  :db.cardinality/one
     :db/doc          (str "Marks an object-identifier as identifying "
                           "a hash-model.")}

    {:db/ident        :pace/prefer-part
     :db/valueType    :db.type/ref
     :db/cardinality  :db.cardinality/one
     :db/doc          (str "Attribute of object-identifiers indicating "
                           "a preferred Datomic partition for "
                           "storing entities of this type.")}

    {:db/ident        :pace/tags
     :db/valueType    :db.type/string
     :db/cardinality  :db.cardinality/one
     :db/doc          (str "Space-separated sequence of tag names "
                           "from the ACeDB model.")}

    {:db/ident         :pace/obj-ref
     :db/valueType     :db.type/ref
     :db/cardinality   :db.cardinality/one
     :db/doc           (str "The object-identifier for the type "
                            "of object referenced by this attribute.")}

    ;; Used to impose an ordering on Datomic components which
    ;; map to complex ACeDB internal tags
    {:db/ident         :pace/order
     :db/valueType     :db.type/long
     :db/cardinality   :db.cardinality/one
     :db/doc           (str "The order of positional parameters "
                            "within a component.")}

    {:db/ident         :pace/use-ns
     :db/valueType     :db.type/string
     :db/cardinality   :db.cardinality/many
     :db/doc           (str "For a component attribute, "
                            "specifies that the component entity may "
                            "contain attributes from the specied "
                            "namespace (e.g. \"evidence\").")}

    {:db/ident         :pace/fill-default
     :db/valueType     :db.type/boolean
     :db/cardinality   :db.cardinality/one
     :db/doc           (str "Hint that the importer should supply a "
                            "default value if none is specified in ACeDB.")}

    {:db/ident         :pace/xref
     :db/valueType     :db.type/ref
     :db/cardinality   :db.cardinality/many
     :db/isComponent   true
     :db/doc           (str "Information about XREFs to this attribute "
                            "from other classes.")}

    {:db/ident         :pace.xref/tags
     :db/valueType     :db.type/string
     :db/cardinality   :db.cardinality/one
     :db/doc           "The XREF's tag-path within this class."}

    {:db/ident         :pace.xref/attribute
     ;; Mainly to ensure we don't get duplicates
     :db/unique        :db.unique/identity
     ;; if we transact this multiple times.
     :db/valueType     :db.type/ref
     :db/cardinality   :db.cardinality/one
     :db/doc           (str "The attribute from the foreign class "
                            "corresponding to this XREF.")}

    {:db/ident         :pace.xref/obj-ref
     :db/valueType     :db.type/ref
     :db/cardinality   :db.cardinality/one
     :db/doc           (str "Identity attribute for the object "
                            "at the outbound end of the XREF.")}

    {:db/ident         :pace.xref/import
     :db/valueType     :db.type/boolean
     :db/cardinality   :db.cardinality/one
     :db/doc           (str "Whether inbound occurrences of this XREF "
                            "be considered by the ACeDB importer.")}

    {:db/ident         :pace.xref/export
     :db/valueType     :db.type/boolean
     :db/cardinality   :db.cardinality/one
     :db/doc           (str "Whether inbound occurrences of this XREF "
                            "be dumped by the .ace file exporter.")}

    {:db/ident         :pace.xref/view
     :db/valueType     :db.type/boolean
     :db/cardinality   :db.cardinality/one
     :db/doc           (str "Should inbound occurrences of this XREF"
                            "be shown in user-oriented viewers.")}

    {:db/ident         :pace.xref/use-ns
     :db/valueType     :db.type/string
     :db/cardinality   :db.cardinality/many
     :db/doc           (str "For 'complex' XREFs, a set of namespaces "
                            "for additional data which should be visible "
                            "on the inbound end.")}]))

(def basetypes-schema
  (map
   conj-install-part
   [{:db/ident        :longtext/id
     :db/valueType    :db.type/string
     :db/unique       :db.unique/identity
     :db/cardinality  :db.cardinality/one
     :db/doc          "Built-in ?LongText class."
     :pace/identifies-class "LongText"}

    {:db/ident        :longtext/text
     :db/cardinality  :db.cardinality/one
     :db/valueType    :db.type/string
     :db/fulltext     true
     :db/doc          (str "The text associated with this object."
                           "A full-text index will be built.")}

    {:db/ident        :dna/id
     :db/valueType    :db.type/string
     :db/unique       :db.unique/identity
     :db/cardinality  :db.cardinality/one
     :db/doc          "Built-in ?DNA class."
     :pace/identifies-class "DNA"}

    {:db/ident        :dna/sequence
     :db/cardinality  :db.cardinality/one
     :db/valueType    :db.type/string
     :db/doc          "The sequence of this DNA."}

    {:db/ident        :peptide/id
     :db/valueType    :db.type/string
     :db/unique       :db.unique/identity
     :db/cardinality  :db.cardinality/one
     :db/doc          "Built-in ?Peptide type."
     :pace/identifies-class "Peptide"}

    {:db/ident        :peptide/sequence
     :db/cardinality  :db.cardinality/one
     :db/valueType    :db.type/string
     :db/doc          "The sequence of this protein/peptide."}

    {:db/ident        :keyword/id
     :db/valueType    :db.type/string
     :db/unique       :db.unique/identity
     :db/cardinality  :db.cardinality/one
     :db/doc          "Built-in ?Keyword type."
     :pace/identifies-class "Keyword"}

    ;;
    ;; Importer support
    ;;

    {:db/valueType    :db.type/string
     :db/cardinality  :db.cardinality/one
     :db/unique       :db.unique/identity
     :db/ident        :importer/temp
     :db/doc          (str "Identifier used as scaffolding by the "
                           "timestamp-aware importer. "
                           "Should generally be excised after import "
                           "is complete.")}

    {:db/valueType    :db.type/string
     :db/cardinality  :db.cardinality/one
     :db/ident        :importer/ts-name
     :db/doc          "Username from a legacy timestamp."}

    ;;
    ;; Special #Ordered virtual hash-model
    ;;

    {:db/ident        :ordered/id
     :db/valueType    :db.type/string
     :db/unique       :db.unique/identity
     :db/cardinality  :db.cardinality/one
     :pace/identifies-class "Ordered"
     :pace/is-hash    true}

    {:db/ident        :ordered/index
     :db/valueType    :db.type/long
     :db/cardinality  :db.cardinality/one
     :db/doc          "Index in an ordered collection."}

    ;; no :pace/tags since we'd never want these to appear in ACeDB-
    ;; style output.

    ;;
    ;; Position_Matrix data
    ;;
    {:db/ident        :position-matrix/background
     :db/valueType    :db.type/ref
     :db/isComponent  true
     :db/cardinality  :db.cardinality/one}

    {:db/ident        :position-matrix/values
     :db/valueType    :db.type/ref
     :db/isComponent  true
     :db/cardinality  :db.cardinality/many
     :pace/use-ns     #{"ordered"}}

    {:db/ident        :position-matrix.value/a
     :db/valueType    :db.type/float
     :db/cardinality  :db.cardinality/one}

    {:db/ident        :position-matrix.value/c
     :db/valueType    :db.type/float
     :db/cardinality  :db.cardinality/one}

    {:db/ident        :position-matrix.value/g
     :db/valueType    :db.type/float
     :db/cardinality  :db.cardinality/one}

    {:db/ident        :position-matrix.value/t
     :db/valueType    :db.type/float
     :db/cardinality  :db.cardinality/one}]))

(def locatable-schema
  (schema
   locatable
   (fields
    ;;
    ;; core attributes, used for all features
    [parent
     :ref
     (str "An entity (e.g. sequence or protein) which defines "
          "the coordinate system for this locatable.")]
    [min :long :indexed
     (str "The lower bound of a half-open (UCSC-style) interval "
          "defining the location.")]
    [max :long :indexed
     (str "The upper bound of a half-open (UCSC-style) interval "
          "defining the location.")]
    [strand :enum [:positive :negative]
     (str "Token designating the strand or orientation "
          "of this feature.  Omit if unknown or irrelevant.")]
    [method :ref
     (str "Method entity defining the meaning of this feature. "
          "Required for lightweight features.")]
    ;;
    ;; Attributes from ?Feature_data and #Feature_info
    ;; -- used for lightweight features
    [score :float
     "Feature score, as used in ?Feature_data."]
    [note :string :many
     "Human-readable note associated with a lightweight feature."]
    ;;
    ;; Binning system
    [murmur-bin :long :indexed
     (str "Bottom 20 bits contain a UCSC/BAM-style bin number."
          "High bits contain a Murmur3 hash code for the parent "
          "sequence.  Only used for locatables attached to a parent "
          "with a :sequence/id.")]
    ;;
    ;; Assembly support
    [assembly-parent :ref
     "The parent sequence in a genome assembly."])))


(def splice-confirm-schemas
  {:splice-confirm (schema
                    splice-confirm
                    (fields
                     [cdna :ref
                      "cdna entity which supports this intron."]
                     [est :ref
                      "sequence entity of an EST which supports this intron."]
                     [ost :ref
                      "sequence entity of an OST which supports this intron."]
                     [rst :ref
                      "sequence entity of an RST which supports this intron."]
                     [mrna :ref
                      "sequence entity of an mRNA which supports this intron."]
                     [utr :ref
                      "sequence entity of a UTR which supports this intron."]
                     [rnaseq :ref :component
                      (str "Details of RNA-seq data supporting this intron "
                           "(uses splice-confirm.rna namespace).")]
                     [mass-spec :ref
                      "mass-spec-peptide entity which supports this intron."]
                     [homology :string
                      (str "accession number of an external database record "
                           "which supports this intron (is this used?).")]
                     [false-splice :ref
                      (str "sequence entity providing evidence for a "
                           "false splice site call.")]
                     [inconsistent :ref
                      (str "sequence entity providing evidence for an "
                           "inconsistent splice site call.")]))
   :rna-seq (schema
             splice-confirm.rnaseq
             (fields
              [analysis :ref
               "Analysis entity describing the RNA-seq dataset."]
              [count :long
               "Number of reads supporting the intron."]))})

(def homology-schema
  (schema
   homology
   (fields
    ;;
    ;; The target of this homology.
    ;; Only one is allowed per homology entity.
    ;;
    [dna :ref
     "Sequence entity representing the target of a DNA homology."]
    [protein :ref
     "Protein entity representing the target of a peptide homology."]
    [motif :ref
     "A motif entity which is mapped to a sequence by this homology."]
    [rnai :ref
     "An RNAi entity which is mapped to a sequence by this homology."]
    [oligo-set :ref
     "An oligo-set which is mapped to a sequence by this homology."]
    [structure :ref
     "Structure-data which is mapped to a sequence by this homology."]
    [expr :ref
     (str "Expression-pattern which is mapped to a sequence "
          "by this homology.")]
    [ms-peptide :ref
     (str "Mass-spec-peptide which is mapped to a sequence "
          "by this homology.")]
    [sage :ref
     "SAGE-tag which is mapped to a sequence by this homology."]
    ;;
    ;; Parent sequence, parent location, and method
    ;; are specified using "locatable".
    ;;
    [min :long :indexed
     (str "Lower bound of a half-open interval defining the extent "
          "of this homology in the target's coordinate system.")]
    [max :long :indexed
     (str "Upper bound of a half-open interval defining the extent "
          "of this homology in the target's coordinate system.")]
    [strand :enum [:positive :negative]
     (str "Token designating the strand or orientation of this "
          "homology on the target's coordinate system. "
          "Should only be used in situations where "
          "a negative-to-negative alignment would be meaningful "
          "(e.g. tblastx)")]
    [gap :string
     (str "Gapped alignment. "
          "The locations of matches and gaps are encoded "
          "in a CIGAR-like format as defined in "
          "http://www.sequenceontology.org/gff3.shtml")]
    ;;
    ;; Parity with legacy #Homol_info
    ;; -- are these needed in the long run?
    ;;
    [target-species :ref
     "Link to target species of alignment."]

    [align-id :string
     "Alignment ID to emit in GFF dumps."])))

(def locatable-schemas
  (generate-schema [locatable-schema
                    (:splice-confirm splice-confirm-schemas)
                    (:rna-seq splice-confirm-schemas)
                    homology-schema]))

(def locatable-extras
  "Add locatable XREFs to the pace schema."
  [{:db/id          (d/tempid :db.part/db)
    :db/ident       :locatable/parent

    ;; this isn't always true, but needed for current Colonnade code.
    :pace/obj-ref   :sequence/id

    :pace/tags      "Parent"}

   {:db/id          (d/tempid :db.part/db)
    :db/ident       :locatable/min
    :pace/tags      "Position Min"}

   {:db/id          (d/tempid :db.part/db)
    :db/ident       :locatable/max
    :pace/tags      "Position Max"}

   {:db/id          (d/tempid :db.part/db)
    :db/ident       :locatable/method
    :pace/obj-ref   :method/id
    :pace/tags      "Method"}

   {:db/id          (d/tempid :db.part/db)
    :db/ident       :locatable/score
    :pace/tags      "Score"}

   {:db/id          (d/tempid :db.part/db)
    :db/ident       :locatable/strand
    :pace/tags      "Strand"}

   {:db/id          (d/tempid :db.part/user)
    :db/ident       :locatable.strand/positive
    :pace/tags      "Positive"}

   {:db/id          (d/tempid :db.part/user)
    :db/ident       :locatable.strand/negative
    :pace/tags      "Negative"}

   {:db/id          (d/tempid :db.part/user)
    :db/ident       :homology.strand/positive
    :pace/tags      "Positive"}

   {:db/id          (d/tempid :db.part/user)
    :db/ident       :homology.strand/negative
    :pace/tags      "Negative"}])

(def top-level-locatable-fixups
  "Classes which have \"locatable\" attributes at top-level."
  [{:db/id          :gene/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :variation/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :feature/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :cds/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :transcript/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :pseudogene/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :transposon/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :pcr-product/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :operon/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :oligo-set/id
    :pace/use-ns    ["locatable"]}

   {:db/id          :expr-profile/id
    :pace/use-ns    ["locatable"]}])

;; See:
;; https://github.com/WormBase/db/wiki/Ace-to-Datomic-mapping#xrefs-in-hash-models
(def component-xref-fixups
  "XREFs inside hash models"
  [{:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :multi-counts.gene/gene
    :pace.xref/obj-ref   :multi-pt-data/id}

   {:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :multi-counts.allele/variation
    :pace.xref/obj-ref   :multi-pt-data/id}

   {:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :multi-counts.locus/locus
    :pace.xref/obj-ref   :multi-pt-data/id}

   {:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :multi-counts.transgene/transgene
    :pace.xref/obj-ref   :multi-pt-data/id}

   {:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :multi-counts.rearrangement/rearrangement
    :pace.xref/obj-ref   :multi-pt-data/id}

   {:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :mass-spec-data/protein
    :pace.xref/obj-ref   :mass-spec-peptide/id}

   {:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :interactor-info/transgene
    :pace.xref/obj-ref   :interaction/id}

   {:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :interactor-info/construct
    :pace.xref/obj-ref   :interaction/id}

   {:db/id               (d/tempid :db.part/user)
    :pace.xref/attribute :interactor-info/antibody
    :pace.xref/obj-ref   :interaction/id}])

(def fixups
  "All the schema fixups together for convenience."
  (concat top-level-locatable-fixups component-xref-fixups))

(defn- transact-silenced
  "Tranact the entity `tx` using `con`.
  Suppresses the (potentially-large) report if it succeeds."
  [con tx]
  @(d/transact con tx)
  nil)

(defn idents-by-ns [db ns-name]
  (sort (d/q '[:find [?ident ...]
               :in $ ?ns-name
               :where
               [?e :db/ident ?ident]
               [_ :db.install/attribute ?e]
               [(namespace ?ident) ?ns]
               [(= ?ns ?ns-name)]]
             db ns-name)))

;; Built-in schemas
;; * Use the d/tempid function to give temporary ids for
;; * Include explicit 1970-01-01 timestamps.
(defn install
  "Installs the various schemata with datomic connection `con`.

  The order the various schemata are transacted in is important."
  [con main-schema & {:keys [no-locatables no-fixups]
                      :or {no-locatables false
                           no-fixups false}}]
  (let [transact (partial transact-silenced con)
        transact-schema #(-> %
                             (mark-tx-early)
                             (transact))
        base-schemas [meta-schema basetypes-schema]]
    (doseq [base-schema base-schemas]
      (transact-schema base-schema))
    (if-not no-locatables
      (transact-schema locatable-schemas))
    (transact-schema main-schema)
    (if-not no-locatables
      (let [loc-schemas [locatable-extras top-level-locatable-fixups]]
        (doseq [loc-schema loc-schemas]
          (transact-schema loc-schema))))
    (if-not no-fixups
      (transact-schema component-xref-fixups))))
