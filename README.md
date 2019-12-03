# pseudoace
[![Clojars Project](https://img.shields.io/clojars/v/wormbase/pseudoace.svg)](https://clojars.org/wormbase/pseudoace)

Provides a Clojure library for use by the [Wormbase][1] project.

Features include:

  * Model-driven import of ACeDB data into a [Datomic][2] database.

      * (Dynamic generation of an isomorphic Datomic schema from an
        annotated ACeDB models file)

  * Conversion of ACeDB database dump files into a datomic database

  * Routines for parsing and dumping ACeDB "dump files".

  * Utility functions and macros for querying WormBase data.

  * A command line interface for utilities described above
	(via the `clj -A:datomic-pro -m pseudoace.cli` command)

## Installation

 * Java 1.8 (OpenJDK or Oracle versions)

 * [Clojure installer and CLI tools][3]

 * [Install datomic][9]

 * The deploy script requires the [xml2][10] utility, install with:
 `sudo apt-get install libxml2`


## Development

  * Feature branches should be derived from the `master` branch.

  * Code review is preferred before merging back into `master`.

### Coding style
This project attempts to adhere to the [Clojure coding-style][7]
conventions.

### Testing
Run all tests regularly, but in particular:

  * before issuing a new pull request

  * after checking out a feature-branch

```bash
clojure -A:datomic-pro:test
```

## Releases

### Initial setup

Run `./scripts/dev-setup.sh` to configure Maven credentials.
(Creates the file `~/.m2/settings.xml`)

### Releasing

#### Library release

`pseudoace` is both a command line application and a library, which is consumed by other WormBase applications.

Clojars is a public repository for packaged clojure libraries.

This release process re-uses the [clojure CLI tools][3]:

  * Checkout the `master` branch if not already checked-out.

	* Update changes entries in the `CHANGELOG.md` file

	* Replace "un-released" in the latest version entry with the
      current date.

	* Update the version of psuedoace in pom.xml.

	* Commit and push all changes.

	* Create git tag matching project version (e.g 0.6.3)

	* Run: `make deploy-clojars`

    * Add a new changelog entry for the next release. commit back to master ("back to development").


#### As a standalone jar file for running the import peer on a server

Will create a release archive based on the latest git TAG.
To override, pass TAG as first argument.

```bash
make uberjar
```

An archive named `pseudoace-$GIT_RELEASE_TAG.tar.xz` will be created
in the `./release-archives` directory.

The archive contains two artefacts:

   ```bash
   cd ./release-archives
   tar tvf pseudoace-$GIT_RELEASE_TAG.tar.xz
   ./pseudoace-$GIT_RELEASE_TAG.jar
   ./sort-edn-log.sh
   ```

1. Create a release on github
2. Upload this tar.xz file as a release asset so the migration pipeline can use it.

> **To ensure we comply with the datomic license
>   ensure this tar file, and specifically  the jar file
>   contained therein is *never* distributed to a public server
>   for download, as this would violate the terms of any proprietary
>   Congnitech Datomic license.**


## Usage

### Development

A command line utility has been developed for ease of usage:

```bash

URL_OF_TRANSACTOR="datomic:dev://localhost:4334/*"
alias run-pace "clj -A:datomic-pro -m pseudoace.cli"
run-pace --url "${URL_OF_TRANSACTOR}" <command>
```

`--url` is a required option for most sub-commands, it should be of
the form of:

`datomic:<storage-backend-alias>://<hostname>:<port>/<db-name>`

Alternatively, for extra speed, one can use the Clojure routines directly
from a repl session:

```bash
# start the repl (Read Eval Print Loop)
clj -A:datomic-pro
```

Example of invoking a sub-command:

```clojure
(require '[environ.core :refer [env]])
(list-databases {:url (env :url-of-transactor)})
```

### Staging/Production

Run `pseudoace` with the same arguments as you would when using `clj`:

  ```bash
  java -cp pseudoace-$GIT_RELEASE_TAG.jar clojure.main -m pseudoace.cli -v
  ```

### Import process

#### Prepare import

Create the database and parse .ace dump-files into EDN.

Example:

```bash
java -cp pseudoace-$GIT_RELEASE_TAG.jar clojure.main \
     -m pseudoace.cli \
     --url $DATOMIC_URL \
	 --acedump-dir ACEDUMP_DIR \
	 --log-dir LOG_DIR \
	 -v prepare-import
```

The `prepare-import` sub-command:

- Creates a new database at the specified `--url`
- Converts `.ace` dump-files located in `--acedump-dir` into pseudo
[EDN][4] files located in `--log-dir`.
- Creates the database schema from the annotated ACeDB models file
specified by `--model`.
- Optionally dumps the newly created database schema to the file
specified by `--schema-filename`.

#### Sort the generated log files

The format of the generated files is:

<ace-db-style_timestamp> <Transactable EDN forms>

The EDN data is *required* to sorted by timestamp in order to
preserve the initial design decision to using Datomic's internal transaction
timestamp to model curation event times:

```bash
find $LOG_DIR \
    -type f \
	-name "*.edn.gz" \
	-exec ./sort-edn-log.sh {} +
```

#### Import the sorted logs into the database

Transacts the EDN sorted by timestamp in `--log-dir` to the database
specified with `--url`:

```bash
java -cp pseudoace-$GIT_RELEASE_TAG.jar clojure.main \
     -m pseudoace.cli \
	 --url URL \
	 --log-dir LOG_DIR \
	 -v import-logs
```

Using a full dump of a recent ACeDB release of WormBase, you can
expect the full import process to take in the region of 48 hours,
dependent on the platform you run it on.

[1]: http://www.wormbase.org/
[2]: http://www.datomic.com/
[3]: https://clojure.org/guides/getting_started
[4]: https://github.com/edn-format/edn/
[5]: http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Introduction.html
[6]: https://datasift.github.io/gitflow/IntroducingGitFlow.html
[7]: https://github.com/bbatsov/clojure-style-guide
[8]: http://clojars.org
[9]: http://docs.datomic.com/getting-started.html
[10]: https://askubuntu.com/questions/733169/how-to-install-libxml2-in-ubuntu-15-10
