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

## Requirements

  * Java 1.8 (OpenJDK or Oracle versions)
  * [Clojure installer and CLI tools][3]
  * [Datomic][6]
  * The deploy script requires the `xml2` utility.
    Ubuntu (20.04) install: `sudo apt-get install xml2`


## Development

Branching & code review
  * Feature branches should be derived from the `master` branch.
  * Code review is preferred before merging back into `master`.

### Coding style
This project attempts to adhere to the [Clojure coding-style][5]
conventions.

### Testing
Run all tests regularly, but in particular:

  * after checking out a feature-branch
  * before issuing a new pull request

```bash
#Test using datomic pro and default clojure version
make run-all-tests
```

## Release & deployment
`pseudoace` is both a command line application and a library, which are consumed by other WormBase applications.
  * The library is consumed by the [Wormbase REST API](http://rest.wormbase.org),
  * The application is used by the [DB-migration](https://github.com/WormBase/db-migration) process
    for running the import peer on a server.

The library is deployed as a thin jar to [Clojars][clojars-pseudoace] (a public repository for packaged clojure libraries),
while the standalone command line application is deployed as an uber jar through [Github package releases](https://docs.github.com/en/github/administering-a-repository/about-releases).

When creating and deploying a new release, you'll need to
  1. Prepare the release
  2. Deploy the standalone application
  3. Deploy the library

If problems pop up during the standalone-application deployment, they still be correct while this is much
harder in Clojars.

### Prepare release
Before being able to start any pseudoace deployment, you'll need to do some (manual) release preparations.
  1. Checkout the `master` branch if not already done so.
  2. Update the [`CHANGELOG.md`](./CHANGELOG.md) file
      * Replace "un-released" in the latest version entry with the current date
        (or add a new section if there's no section yet for the current release)
      * Represent all changes made for this release.
  4. Update the version of pseudoace in the `pom.xml` file.
  5. Commit and push all changes.
  6. Create git tag matching project version (e.g 0.6.3). Use annotated tags (git CLI option `-a`).


### Deploy standalone application

The following command will create a release archive based on the latest git tag (created [above](#prepare-release)).
```bash
make uberjar
```

An archive named `pseudoace-${GIT_RELEASE_TAG}.tar.xz` will be created
in the `./release-archives` directory.

List the content of the created archive
```bash
tar -tf release-archives/pseudoace-${GIT_RELEASE_TAG}.tar.xz
```
The archive should contain two artefacts:
```
pseudoace-${GIT_RELEASE_TAG}/
pseudoace-${GIT_RELEASE_TAG}/pseudoace-${GIT_RELEASE_TAG}.jar
pseudoace-${GIT_RELEASE_TAG}/sort-edn-log.sh
```

**IMPORTANT NOTE!**  
As we use a proprietary Datomic license in some code, we need to ensure we comply with the license.
Datomic free can be freely distributed, but **datomic-pro cannot**. Uber jars containing datomic-pro
assest can **never be distributed** to a **public** server for download, as this would **violate**
the terms of any proprietary Congnitect Datomic **license**.

As the tar file created above will be deployed publically, ensure this tar file, and specifically the uber-jar file contained therein, does **not contain any datomic-pro assets**!
```bash
tar -xOf ./release-archives/pseudoace-$GIT_RELEASE_TAG.tar.xz pseudoace-$GIT_RELEASE_TAG/pseudoace-$GIT_RELEASE_TAG.jar | jar -tv | grep -P "datomic-(free|pro)"
```
This command should only return `datomic-free` artifacts and **no `datomic-pro` ones!**

Once confirmed, [create a new release on github](https://docs.github.com/en/github/administering-a-repository/managing-releases-in-a-repository#creating-a-release)
  * As tag version, use the same as name as the tag create when [preparing the release](#prepare-release)
  * As target-branch, use `master`
  * As the release title, repeat the release tag name and refer to the CHANGELOG in the description
  * Upload the tar.xz file from above as a release asset (called `binaries`) so the migration pipeline can use it.

### Deploy library

In order to deploy to [Clojars][clojars-pseudoace], the `~/.m2/settings.xml` needs to define
clojars deploy credentials which allow write access to the Clojars wormbase group.
For instructions on how to define this file, see [credentials setup](#credentials-setup).

Run:
```bash
make deploy-clojars
```

#### Credentials setup
Deployment to Clojars require the file `~/.m2/settings.xml` to be defined as described [here](https://github.com/clojars/clojars-web/wiki/pushing#maven) (`settings.xml` part).
Clojars username can be obtained by registering at [clojars.org](https://clojars.org)
and a deploy-token can be generated after that by visiting [this page](https://clojars.org/tokens).
Ensure you have been added to the wormbase group to allow uploading a new version (ask a colleague).

You can execute `./scripts/dev-setup.sh` to generate a credentials file as describe above
and provide your user name and deploy token.

## Usage

### Development
For any development usage of the code, ensure your
working directory is set to the repository root.

A command line utility has been developed for ease of usage.
  * `--url` is a required option for most sub-commands, it should be of the form of:  
    `datomic:<storage-backend-alias>://<hostname>:<port>/<db-name>`

```bash
URL_OF_TRANSACTOR="datomic:dev://localhost:4334/*"
alias run-pace "clj -A:datomic-pro -m pseudoace.cli"
run-pace --url "${URL_OF_TRANSACTOR}" <command>
```

Alternatively, for extra speed and flexibility, one can
call the Clojure routines directly in a REPL session:

```bash
# start the REPL (Read Eval Print Loop)
clj -A:datomic-pro
```

Example of invoking a sub-command:
```clojure
(require '[environ.core :refer [env]])
(list-databases {:url (env :url-of-transactor)})
```

### Staging/Production

Run the `pseudoace` jar with the same arguments as you would when using `clj`:
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
  * Creates a new database at the specified `--url`
  * Converts `.ace` dump-files located in `--acedump-dir` into pseudo
    [EDN][4] files located in `--log-dir`.
  * Creates the database schema from the annotated ACeDB models file
    specified by `--model`.
  * Optionally dumps the newly created database schema to the file
    specified by `--schema-filename`.

#### Sort the generated log files

The format of the generated files' content is:  
`<ace-db-style_timestamp> <Transactable EDN forms>`

The EDN data is *required* to be sorted by timestamp in order to
preserve the initial design decision to using Datomic's internal transaction
timestamp to model curation event times.

To sort the EDN log files:
```bash
find $LOG_DIR \
    -type f \
	-name "*.edn.gz" \
	-exec ./sort-edn-log.sh {} +
```

#### Import the sorted logs into the database

Transact the EDN sorted by timestamp in `--log-dir` to the database
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

[clojars-pseudoace]: https://clojars.org/wormbase/pseudoace/
[1]: http://www.wormbase.org/
[2]: http://www.datomic.com/
[3]: https://clojure.org/guides/getting_started
[4]: https://github.com/edn-format/edn/
[5]: https://github.com/bbatsov/clojure-style-guide
[6]: http://docs.datomic.com/getting-started.html
