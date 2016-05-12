# Change-log

## 0.1.0
 - Initial version

## 0.2.0
 - Re-organised project structure.
 - Separation of components into separate repositories.
 - Added basic unit test structure.
 - Removed dependency on perl for sorting EDN files.
 - Fixed minor issues with command line interface.

## 0.3.0
 - Fix error with old-namespace:
	`acetyl` is no longer used, and has been in-lined into the
    pseudoace package.

## 0.3.1
 - Added ability to generate a report for comparing a dump of classes to values
   against those stored in the database.

## 0.4 - (2016-04-25)
 - Moved sorting of logs files back out to the shell.
 - Fixed lingering namespace issues in `pseudoace.acedump`.
 - Use `datomic.api.tempid` in code, not reader-literals.
 - Use the script `scripts/bundle-release.sh` to create a bundle for
   deploying to servers and running the importer.
 - Updated README (development and release instructions).
 - Added development tools to "dev" profile in leiningen project configuration.
 - Enable multiple storage profiles in lein project.
 - Added script to generate release bundle.
 - Models file is now defaults to `models/models.wrm.annot` (no need
   to pass on command line to sub-commands)
 - Unified the various additional schemata into a single module.
 - Import will no longer attempt to transact EDN logs older than the
   latest transaction date.
 - Report now generates two files per class when non-zero missing and
   added counts are encountered for easy diffing of object names.

## 0.4.1 - (2016-04-28)
  - Use `lein release` for releases.
  - Bump versions of datomic and storage dependencies.

## 0.4.2 - (2016-04-28)
  - Updated documentation.

## 0.4.3 - (2016-04-28)
  - Rollback version of aws library.

## 0.4.4 - (2016-05-12)
  - Support distributing a release bundle using either datomic-free or datomic-pro.
  - Add generated schema for WS253, WS254
  - Various minor fixes for the command line interface.

## 0.4.5 - (un-released)
  - Nothing changed yet.
