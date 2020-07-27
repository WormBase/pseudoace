# ACeDB to Datomic Import

## Preparation

The importer process uses a dump of the target ACeDB -- it never actually connects to ACeDB directly.  Make a dump from the xace "admin" menu, being sure to include timestamps.  It's fine to include comments as well, but they will be ignored by the importer.

Once the dump is complete, I usually compress it with gzip (ace files are huge! - compressed ~ 2.2G).  Make a note of the highest dump-file number.

On the development server you can run the following command to get the latest release:

```bash
        tar -zcvf acedb-WS25-2015-09-04.tar.gz /usr/local/wormbase/tmp/staging/<release>/acedmp
```

Once on the computer the data will be loaded from unzip the folder and then zip the individual ace file
      
```bash
        tar xvfz acedb-WS250-2015-09-04.tar.gz  #to extract the directory
        for file in $(ls *.ace); do gzip "$file"; done # to zip each file in the directory individually
```

Here is a script that I used to zip the files and rename them according to how the import script requires them.

```bash
        a=1
        for i in *.ace.gz; do
                new=$(printf "dump_2015-02-19_A.%d.ace.gz" "$a") #04 pad to length of 4
                mv -- "$i" "$new"
                let a=a+1
        done
```

Then secure copy the file onto the machine you are working on

### Alternatively, dump out the ACE data using tace:

```bash
        % mkdir -p /nfs/panda/ensemblgenomes/wormbase/tmp/WS250_dump
        % tace /nfs/panda/ensemblgenomes/wormbase/DATABASES/current_DB/
        > Dump -T /nfs/panda/ensemblgenomes/wormbase/tmp/WS250_dump     # NB it doesn't work if there is a '/' at the end of the path
        > quit
```

## Command line interface

To get information on what the ACeDB to Datomic import tool provides run the following command

```bash
        cd db/pseudoace;  
        lein run ace-to-datomic --help
```

```
Pseudoace is tool for importing data from ACeDB into to Datomic database

Usage: pseudoace [options] action

Options:
      --model PATH            Specify the model file that you would like to use that is found in the models folder e.g. models.wrm.WS250.annot
      --url URL               Specify the url of the Dataomic transactor you would like to connect. Example: datomic:free://localhost:4334/WS25
      --schema-filename PATH  Specify the name of the file for the schema view to be written to when selecting Action: generate-schema-view exampls schema250.edn
      --log-dir PATH          Specifies the path to and empty directory to store the Datomic logs in. Example: /datastore/datomic/tmp/datomic/import-logs-WS250/
      --acedump-dir PATH      Specifies the path to the directory of the desired acedump. Example /datastore/datomic/tmp/acedata/WS250/
  -v, --verbose
  -h, --help

Actions:
  create-database                      Select this option if you would like to create a Datomic database from a schema. Required options [model, url]
  generate-datomic-schema-view         Select if you would like the schema to the database to be exported to a file. Required options [schema-filename, url]
  acedump-to-datomic-log               Select if you are importing data from ACeDB to Datomic and would like to create the Datomic log files [url, log-dir, acedump-dir]
  sort-datomic-log                     Select if you would like to sort the log files generated from your ACeDB dump [log-dir]
  import-logs-back-into-datomic        Select if you would like to import the sorted logs back into datomic [log-dir, url]
  all-actions                          Select if you would like to perform all actions from acedb to datomic [all options required]
```

The paths should be full paths and the actions required options are listed within their corresponding square brackets. 

I anticipate that most of the time people will be using the tool to run from beginning to end and will choose the action "all actions". Other then this flag the actions are listed individually and in the order they should be run.

### Example Command
```bash
lein run ace-to-datomic all-import-actions --url=datomic:ddb://us-east-1/wormbase/WS252 --log-dir=/datastore/datomic/tmp/datomic/import-logs-WS252/ --model=models.wrm.WS252.annot --acedump-dir=/datastore/datomic/dumps/WS252_dump/ --schema-filename=schema252.edn -v
```

## Converting ace dumps to Datomic-log format

This assumes that the acefiles are gzipped, which saves lots of space and certainly won't harm performance.  The log files are also written in gzip format (specifically, sequences of short gzip streams -- which are legal).

 

## Sorting log segments (10h 40 min)

Sort in timestamp order because datomic needs this. This bit is currently done from the shell.  Some segments are large and take a while to sort -- it may be possible to improve this by throwing RAM at the problem...
     
## Playing logs back into Datomic 

Approximately 36 hours and requires at least 120GB of disk space


## Test it works

Do a quick query to test if the database has read in OK.

## Garbage collection

If you are very low on space, like doing a full database import with only 50Gb disk space free, you might have to do garbage collection during playing the files into Datomic to save disk space.

To do garbase collection, you give the following command using a second repl session connected to the first one. You can attach multiple repls to the same repl session with the command `lein repl :connect` which connects to the port of the existing repl.

Any garbage older than the specified time will be collected, so give a recent time:

```clojure
        (datomic.api/gc-storage con #inst "2015-08-27T16:00")
```

Running gc-storage occasionally during the log-replay phase helps keep storage requirements down somewhat, but they'll still climb to be substantially higher than reimporting the finished database into clean storage.  Note that `gc-storage` may take several hours -- watch the logs for `:kv-cluster/delete` events to see how things are going - seeing these lines in the log file simply indicates that garbage collection is proceeding as expected.

It's definitely worth running `gc-storage` before excising the scaffolding IDs, and (if you're not doing a full dump and restore) afterwards as well.

## When import has finished

Once all the log segments are replayed, your DB is ready to test.  

## Test it works

Once all the log segments are replayed, your DB is ready to test. 
Do a quick query to test if the database has read in OK.

```clojure
(d/q '[:find ?c :in $ :where [?c :gene/id "WBGene00018635"]] (d/db (d/connect uri)))
```
gives:
```clojure
#{[923589767780191]}
```

## When import has finished

Once you're happy with it, you'll probably want to do:

```clojure
     (d/transact con [{:db/id #db/id[:db.part/user] 
                       :db/excise :importer/temp}])
```

To clear-out all the import scaffolding IDs.  Note that while the transaction will complete very quickly, the actual excision job runs asynchronously and will take quite a while (1 hour?).  You'll still see the :importer/temp attributes until the whole excision has completed.

## Backup and Restore

Finally, the DB storage will be quite big at this point.  You can save much space by doing a `datomic backup-db`, then a `datomic restore-db` into clean storage.

```bash
./bin/datomic -Xmx4g -Xms4g backup-db "datomic:free://localhost:4334/WS251" "file:/datastore/datomic/dumps/WS251_dump_backup"
```

Basically: backup-db, kill the transactor, delete the "data" directory, restart the transactor, then run restore-db.


### To backup a database (approximately 1.5 hours)

The transactor must be running.

Shut down all unused repls and groovey shells using up memory otherwise you will run out of memory.

Make the dump directory and set it to be group writeable, then dump into it:
```bash
sudo mkdir -p /datastore/datomic/dumps/WS250_dump
sudo chmod g+w /datastore/datomic/dumps/WS250_dump
bin/datomic -Xmx4g -Xms4g backup-db "datomic:free://localhost:4334/WS250" "file:/datastore/datomic/dumps/WS250_dump"
```

### To restore the database

If you wish to restore to a fresh data file, then remove the old 'data' directory (as pointed to by the transactor config file) and restart the transactor:

Kill the transactor (use `ps -eF | grep datomic.launcher` to find the PID of the transactor)
Delete or Move away the directory holding the storage file: `/mnt/data/datomic-free-0.9.5130/data`

Start the transactor again:
```bash
# start a screen or tmux session for the transactor to run in
screen -S transactor -h 10000	
cd /mnt/data/ datomic-free-0.9.5130
export XMX=-Xmx4G
sudo bin/transactor -Xmx4G -Xms4G config/transactor.properties &
```
The transactor must now be running.

Restore the DB
 ```bash
 bin/datomic -Xmx4g -Xms4g restore-db "file:datastore/datomic/backups/database_name_dump" "datomic:free://localhost:4334/name_of_database"
 ```

Backup-and-restore can be used to rename a database.

You cannot restore a single database to two different URIs (two names) within the same storage. i.e. you cannot make a copy of a database with a different name in the same storage file.

You must kill and restart peers (repls) and transactors after a restore. 

Time: ~ 5 mins reading geneace into an existing storage file
