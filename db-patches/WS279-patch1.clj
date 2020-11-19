;;Datomic DB patch for WS279-patch1
;;WS279 dynamoDB was a fresh export/import from the DB-migration pipeline (done by OICR)
(require '[datomic.api :as d])
(def uri "datomic:ddb://us-east-1/WS279/wormbase")
(def conn (d/connect uri))
(def db (d/db conn))

;Query which identifies all VC2010 genes (with a PRJEB28388_ prefix on their :gene/id attribute)
;(d/q '[:find ?gpname ?gid :where [?e :gene/public-name ?gpname] [?e :gene/id ?gid] [(re-matches #"PRJEB28388_.+" ?gid)]] db)

;Query which identifies all VC2010 genes (with a PRJEB28388_ prefix on their :gene/id attribute)
; that have a wormbaseID as public-name (WBGene*)
;(d/q '[:find ?gpname ?gid :where [?e :gene/public-name ?gpname] [?e :gene/id ?gid] [(re-matches #"PRJEB28388_.+" ?gid)] [(re-matches #"WBGene\d+" ?gpname)]] db)

;Find and count all genes with a public-name that conflicts with another gene's :gene/id
;(count (d/q '[:find ?e ?gpname ?gid ?othere :where [?e :gene/public-name ?gpname] [?e :gene/id ?gid] [?othere :gene/id ?gpname]] db))

;Store the entity IDs for which to reset the public-name
(def conflicting-eids (d/q '[:find [?e ...] :where [?e :gene/public-name ?gpname] [?e :gene/id ?gid] [?othere :gene/id ?gpname]] db))

;Retract public-name for every found conflicting-eid
(let
 [build-tx (fn [id] (vec [:db/retract id :gene/public-name]))
  txes (map #(build-tx %) conflicting-eids)]
  (d/transact-async conn txes))

;;To check transaction completed:
;(def queue (d/tx-report-queue conn))
;(.poll queue) ;repeat polling until it returns nil
