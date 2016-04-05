# pseudoace

Provides a clojure library for use by the [Wormbase][1] project.

Features include:

  * Model-driven import of ACeDB data into a [Datomic][2] database.
    * (Dynamic generation of an isomorphic Datomic schema from an
      annotated ACeDB models file)
  * Conversion of ACeDB database dump files into a datomic database
  * Routines for parsing and dumping ACeDB "dump files".
  * Utility functions and macros for querying WormBase data.
  * A command line interface for utilities described above (via `lein run`)
  
## Installation

Fedora/Redhat:

```bash
sudo yum -y install perl-autodie perl-IPC-System-Simple
```

Debian/Ubuntu:

```bash
sudo apt-get install -y libautodie-perl libipc-system-simple-perl
```


[Install leiningen][3].

## Development

Follow the [GitFlow][6] mechanism for branching and committing changes.

Please attempt to adhere to the [Clojure coding-style][7] conventions.

## Usage

A command line utility has been developed for ease of usage:

```bash

URL_OF_TRANSACTOR="datomic:dev://localhost:4334/*"

lein run --url $URL_OF_TRANSACTOR <command>

```

`--url` is a required option for most sub-commands, it should be of
the form of:

`datomic:<storage-backend-alias>://<hostname>:<port>/<db-name>`

Alternatively, for extra speed, one can use the clojure routines directly
from a repl session:

```bash
# start the repl (Read Eval Print Loop)
lein repl
```

Example of invoking a sub-command:

```clojure
(list-databases {:url (System/getenv "URL_OF_TRANSACTOR")})
```

## Example command

The `import-all-actions` sub-command performs a "full import":

  * Converts `ACeDB` dump files into [EDN][4] files.
  * Sorts EDN files by timestamp.
  * Dynamically creates the database schema based upon ACeDB annotated models.
  *	Imports sorted timestamp datoms derived from the processed EDN files.
  

The following command uses the [DynamoDB storage back-end][5],
configured to use a database located in the Amazon cloud:

```bash
lein run \
     --url="datomic:ddb://us-east-1/wormbase/WS252" \
	 --log-dir=/datastore/datomic/tmp/datomic/import-logs-WS252/ \
	 --model=models.wrm.WS252.annot  \
	 --acedump-dir=/datastore/datomic/dumps/WS252_dump/ \
	 --schema-filename=schema252.edn -v
```

Using a full dump of a recent release of Wormbase, you can expect this
command to take in the region of 8-12 hours depending on the platform
you run it on.

[1]: http://www.wormbase.org/
[2]: http://www.datomic.com/
[3]: http://leiningen.org/
[4]: https://github.com/edn-format/edn/
[5]: http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Introduction.html
[6]: https://datasift.github.io/gitflow/IntroducingGitFlow.html
[7]: https://github.com/bbatsov/clojure-style-guide
