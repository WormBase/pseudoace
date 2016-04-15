#!/bin/sh
# Script for creating an archive of all the neccessary artifacts
# for a given (git) tagged version.

run_step() {
    echo "Running $@"
    $@ || exit 1
}

release_file() {
    local release="$1"
    local source_path="$2"
    local target_path="$3"
    git show "$release:$source_path" > "$target_path"
}


pre_release_check() {
    local msg="$1"
    shift
    $@ || (echo "ERROR: $msg" && exit 1)
}

pre_release_checks() {
    pre_release_check "All linting tests must pass!" lein eastwood
    pre_release_check "All tests must pass!" lein test
}

RELEASE_TAG="$1"

if [ -z "$RELEASE_TAG" ]; then
    echo "USAGE: $0 <GIT_RELEASE_TAG>"
    exit 1
fi

PROJ_NAME="pseudoace"
BUILD_DIR="/tmp/$PROJ_NAME-release-$RELEASE_TAG"
LOG_SORT_SCRIPT="scripts/sort-edn-log.sh"
DEPLOY_JAR="$BUILD_DIR/$PROJ_NAME-$RELEASE_TAG.jar"
RELEASE_DIR="$PWD/release-archives"
RELEASE_ARCHIVE="$RELEASE_DIR/$PROJ_NAME-$RELEASE_TAG.tar.gz"
ANNOT_MODELS_FILE="models/models.wrm.annot"

pre_release_checks
echo "Pre-release checks passed"
echo "Generating release archive"
echo "--------------------------"
run_step mkdir -p "$BUILD_DIR"
run_step mkdir -p "$RELEASE_DIR"
release_file "$RELEASE_TAG" \
	     "$LOG_SORT_SCRIPT" \
	     "$BUILD_DIR/$(basename $LOG_SORT_SCRIPT)"
run_step lein do clean, uberjar
run_step \
    mv "$PWD/target/$PROJ_NAME-$RELEASE_TAG-standalone.jar" \
       "$DEPLOY_JAR"
cd "$BUILD_DIR"
run_step tar jcf "$RELEASE_ARCHIVE" .
cd -
echo "REMOVE $BUILD_DIR"
echo rm -rf "$BUILD_DIR"
echo "$RELEASE_ARCHIVE created in $RELEASE_DIR."
