# pseudoace

Provides a Clojure library for use by the [Wormbase][1] project.

Features include:

  * Model-driven import of ACeDB data into a [Datomic][2] database.
    * (Dynamic generation of an isomorphic Datomic schema from an
      annotated ACeDB models file)
  * Conversion of ACeDB database dump files into a datomic database
  * Routines for parsing and dumping ACeDB "dump files".
  * Utility functions and macros for querying WormBase data.
  * A command line interface for utilities described above (via `lein run`)

## Installation

 * Java 1.8 (Prefer official oracle version)

 * [leiningen][3].

 * Datomic
   * Visit https://my.datomic.com/downloads/pro
   * Download the version of datomic-pro that matches the version
	 specified in `project.clj`.
   * When upgrading datomic, download the latest version and update
     `project.clj` accordingly.
   * Unzip the downloaded archive, and run: `bin/maven-install`

## Development

Follow the [GitFlow][6] mechanism for branching and committing changes:

  * Feature branches should be derived from the `develop` branch:
    i.e:. git checkout -b feature-x develop

### Coding style
This project attempts to adhere to the [Clojure coding-style][7] conventions.

### Testing & code QA
Run all tests regularly, but in particular:

  * before issuing a new pull request

  * after checking out a feature-branch
  
#### eastwood
Eastwood is a popular linting tool for Clojure.

Run by itself with:
```bash
lein with-profile dev eastwood
```

Other useful leiningen plugins for development include:

#### kibit 
Recommend source idiomatic code changes.

There is editor support in Emacs. e.g: `M-x kibit-current-file`

Command line examples:

  ```bash
  # whole project
  lein with-profile dev kibit
  # single file
  lein with-profile dev kibit src/pseudoace/core.clj
  ```
#### bikeshed
Report changes which ideally need fixing


### Required checks
Run all *required* development QA steps with:

 ```bash
 
 lein with-profile dev eastwood
 lein with-profile dev test
 
 ```

## Releases

### Initial setup

[Configure leiningen credentials][9] for [clojars][8].

### Procedure

  * On the `develop` branch:
	
	* Add an entry in the CHANGES.md file.

	* Change the version in the leiningen project.clj

	  From:
		`<major.minor.patch>-SNAPSHOT`

	  To:
		`<major.minor.patch>`
		
	  and push these changes.
  
  * Merge the `develop` branch into to `master` (via a github pull
    request or directly using git)

  * Create an *annotated* tag in git, using the same version as defined in
    project.clj:
	
	  ```bash
	  git tag -a $VERSION -m "Releasing $VERSION"`
	  git push --tags
	  ```
  * Deploy to [clojars][8] via leiningen:
      `line deploy clojars`

	Depending on your credentials setup,
	you may be prompted for your clojars surname and password.

  * Checkout the develop branch, update CHANGES.md with the next version
    number and a "back to development" stanza:

	e.g:
	```markdown
	# 0.3.2 - (unreleased)
	  - nothing changed yet
	```
	Update the version in project.clj to be:

	  `<next-major-version>.<next-minor>.<next-patch>-SNAPSHOT`

	commit and push these changes.


### Deployment

Deploy as a library to [clojars][8]:

```bash
git checkout $RELEASE_TAG
lein deploy clojars
```

Create a bundle of the release for running the import on a server:

```bash
./scripts/bundle-release.sh $GIT_RELEASE_TAG
```

An archive named `pseudoace-$GIT_RELEASE_TAG.tar.gz` will be created in the
`release-archives` directory.

The archive contains two artefacts:

   ```bash
   tar tvf pseudoace-$GIT_RELEASE_TAG.tar.gz
   ./pseudoace-$GIT_RELEASE_TAG.jar
   ./sort-edn-log.sh
   ```
  
## Usage

### Development

A command line utility has been developed for ease of usage:

```bash

URL_OF_TRANSACTOR="datomic:dev://localhost:4334/*"

lein run --url $URL_OF_TRANSACTOR <command>

```

`--url` is a required option for most sub-commands, it should be of
the form of:

`datomic:<storage-backend-alias>://<hostname>:<port>/<db-name>`

Alternatively, for extra speed, one can use the Clojure routines directly
from a repl session:

```bash
# start the repl (Read Eval Print Loop)
lein repl
```

Example of invoking a sub-command:

```clojure
(list-databases {:url (System/getenv "URL_OF_TRANSACTOR")})
```

### Staging/Production

Run `pseudoace` with the same arguments as you would when using `lein run`:

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

<ace-db-style_timestamp> <Parsed ACE data to be transacted in EDN format>

The EDN data is *required* to sorted by timestamp in order to
preserve the time invariant of Datomic:

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

Using a full dump of a recent release of Wormbase, you can expect the
import process to take in the region of 8-12 hours depending on the
platform you run it on.

[1]: http://www.wormbase.org/
[2]: http://www.datomic.com/
[3]: http://leiningen.org/
[4]: https://github.com/edn-format/edn/
[5]: http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Introduction.html
[6]: https://datasift.github.io/gitflow/IntroducingGitFlow.html
[7]: https://github.com/bbatsov/clojure-style-guide
[8]: http://clojars.org
[9]: https://github.com/technomancy/leiningen/blob/master/doc/DEPLOY.md#authentication
