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
	(via the `clojure -A:datomic-pro -m clojure.main pseudoace.cli` command)

## Installation

 * Java 1.8 (Prefer official oracle version)

 * [leiningen][3]

   * You will also need to specify which flavour and version of
     datomic you want use in your [lein peer project configuration][13].

     Example:

     ```clojure
     (defproject myproject-0.1-SNAPSHOT
        :dependencies [[com.datomic/datomic-free "0.9.5359"
                        :exclusions [joda-time]]
                       [wormbase/pseudoace "0.4.4"]])
     ```

 * [Install datomic][14]


## Development

Follow the [GitFlow][6] mechanism for branching and committing changes:

  * Feature branches should be derived from the `develop` branch:
    i.e:. git checkout -b feature-x develop

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

[Configure leiningen credentials][9] for [clojars][8].

Test your setup by running:
```bash
# Ensure you are Using `gpg2`, and the `gpg-agent` is running.
# Here, gpg is a symbolic link to gpg2
gpg --quiet --batch --decrypt ~/.lein/credentials.clj.gpg
```

The output should look like (credentials elided):

```
;; my.datomic.com and clojars credentials
{#"my\.datomic\.com" {:username ...
                      :password ...}
 #"clojars" {:username ...
             :password ...}}
```

### Releasing to Clojars

Clojars is a public repository for packaged clojure libraries.

This release process re-uses the [leiningen deployment tools][12]:

  * Checkout the `develop` branch if not already checked-out.

	* Update changes entries in the `CHANGELOG.md` file

	* Replace "un-released" in the latest version entry with the
      current date.

	* Change the version from `MAJOR.MINOR.PATCH-SNAPSHOT` to
      `MAJOR.MINOR.PATCH` in `project.clj`.

	* Commit and push all changes.

  * Checkout the `master` branch.

	* Merge the `develop` branch into to `master` (via a github pull
      request or directly using git)

	* Run:

		`lein deploy clojars`

  * Checkout the `develop` branch.

	* Merge the `master` branch back into `develop`.

	* Change the version from `MAJOR.MINOR.PATCH` to
      `MAJOR.MINOR.PATCH-SNAPSHOT` in `project.clj`.

	* Update `CHANGELOG.md` with the next
      version number and a "back to development" stanza, e.g:

	```markdown
	## 0.3.2 - (unreleased)
	  - Nothing changed yet.
	```

    Commit and push these changes, typically with the message:

		"Back to development"

#### As a standalone jar file for running the import peer on a server

```bash
# GIT_RELEASE_TAG should be the annotated git release tag, e.g:
#   GIT_RELEASE_TAG="0.3.2"
#
# If you want to use a local git tag, ensure it matches the version in
# projet.clj, e.g:
#  GIT_RELEASE_TAG="0.3.2-SNAPSHOT"
#
# LEIN_PROFILE
# should be:
#   - "prod" (for datomic-pro, ddb release)
#   - "dev" (for open-source release)
# e.g:
git checkout "${GIT_RELEASE_TAG}" "dev"
./scripts/bundle-release.sh $GIT_RELEASE_TAG $LEIN_PROFILE
```

An archive named `pseudoace-$GIT_RELEASE_TAG.tar.gz` will be created
in the `./release-archives` directory.

The archive contains two artefacts:

   ```bash
   cd ./release-archives
   tar tvf pseudoace-$GIT_RELEASE_TAG.tar.gz
   ./pseudoace-$GIT_RELEASE_TAG.jar
   ./sort-edn-log.sh
   ```

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
alias run-pace "clj -A:datomic-pro:aws-java-sdk-dynamodb -m pseudoace.cli"
run-pace --url "${URL_OF_TRANSACTOR}" <command>
```

`--url` is a required option for most sub-commands, it should be of
the form of:

`datomic:<storage-backend-alias>://<hostname>:<port>/<db-name>`

Alternatively, for extra speed, one can use the Clojure routines directly
from a repl session:

```bash
# start the repl (Read Eval Print Loop)
clj -A:datomic-pro:aws-java-sdk-dynamodb
```

Example of invoking a sub-command:

```clojure
(require '[environ.core :refer [env]])
(list-databases {:url (env :url-of-transactor)})
```

### Staging/Production

Run `pseudoace` with the same arguments as you would when using `lein
run`:

  ```bash
  java -jar pseudoace-$GIT_RELEASE_TAG.jar -v
  ```

### Import process

#### Prepare import

Create the database and parse .ace dump-files into EDN.

Example:

```bash
java -jar pseudoace-$GIT_RELEASE_TAG.jar \
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
java -jar pseudoace-$GIT_RELEASE_TAG.jar \
	 --log-dir LOG_DIR \
	 -v import-logs
```

Using a full dump of a recent ACeDB release of WormBase, you can
expect the full import process to take in the region of 48 hours,
dependent on the platform you run it on.

[1]: http://www.wormbase.org/
[2]: http://www.datomic.com/
[3]: http://leiningen.org/
[4]: https://github.com/edn-format/edn/
[5]: http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Introduction.html
[6]: https://datasift.github.io/gitflow/IntroducingGitFlow.html
[7]: https://github.com/bbatsov/clojure-style-guide
[8]: http://clojars.org
[9]: https://github.com/technomancy/leiningen/blob/master/doc/DEPLOY.md#authentication
[10]: https://github.com/jonase/kibit
[11]: https://github.com/dakrone/lein-bikeshed
[12]: https://github.com/technomancy/leiningen/blob/master/doc/DEPLOY.md#deployment
[13]: http://docs.datomic.com/integrating-peer-lib.html
[14]: http://docs.datomic.com/getting-started.html
