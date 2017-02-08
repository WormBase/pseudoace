(ns pseudoace.binning
  (:import clojure.lang.Murmur3))

;;
;; Raw binning functions based on BAM spec.
;;

(defn xbin [id x]
  (bit-or x (bit-shift-left (bit-and id 0x3ffffffffff) 16)))

(defn reg2bin [beg end]
  (let [end (dec end)]
    (cond
     (= (bit-shift-right beg 14) (bit-shift-right end 14))
     (+ (/ (dec (bit-shift-left 1 15)) 7) (bit-shift-right beg 14))

     (= (bit-shift-right beg 17) (bit-shift-right end 17))
     (+ (/ (dec (bit-shift-left 1 12)) 7) (bit-shift-right beg 17))

     (= (bit-shift-right beg 20) (bit-shift-right end 20))
     (+ (/ (dec (bit-shift-left 1 9)) 7) (bit-shift-right beg 20))

     (= (bit-shift-right beg 23) (bit-shift-right end 23))
     (+ (/ (dec (bit-shift-left 1 6)) 7) (bit-shift-right beg 23))

     (= (bit-shift-right beg 26) (bit-shift-right end 26))
     (+ (/ (dec (bit-shift-left 1 3)) 7) (bit-shift-right beg 26))

     :default
     0)))

(defn reg2bins [beg end]
  (concat
   [0]
   (range (+ 1 (bit-shift-right beg 26)) (+ 1 1 (bit-shift-right end 26)))
   (range (+ 9 (bit-shift-right beg 23)) (+ 9 1 (bit-shift-right end 23)))
   (range (+ 73 (bit-shift-right beg 20)) (+ 73 1 (bit-shift-right end 20)))
   (range (+ 585 (bit-shift-right beg 17)) (+ 585 1 (bit-shift-right end 17)))
   (range (+ 4681 (bit-shift-right beg 14)) (+ 4681 1 (bit-shift-right end 14)))))

(defn bin
  "Return a WB bin number for features overlapping the region from
  `coord-min` to `coord-max` attached to `s`.

  (Where `s` may be found for example by: `[:sequence/id seq]`)."
  [^String s coord-min coord-max]
  (bit-or
   (bit-shift-left (Murmur3/hashUnencodedChars s) 20)
   (reg2bin coord-min coord-max)))

(defn bins
  "Return WB bin numbers for features overlapping the region from
  `coord-min` to `coord-max` attached to `s`.

  (Where `s` may be found for example by: `[:sequence/id seq]`)."
  [^String s coord-min coord-max]
  (let [bits (bit-shift-left (Murmur3/hashUnencodedChars s) 20)]
    (mapv (partial bit-or bits) (reg2bins coord-min coord-max))))
