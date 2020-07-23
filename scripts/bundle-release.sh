#!/bin/bash
# -*- coding: utf-8 -*-
# Script for creating an archive of all the necessary artefact's
# for a given (git) tagged version.

source "$(dirname $0)/functions.sh"

pre_release_check() {
    local msg="$1"
    shift
    inform "Running pre-release check: $* " -n
    $* &> "${LOGFILE}"
    if [ $? -ne 0 ]; then
        echo "ERROR: ${msg} ❌"
        exit 1
    fi
    echo "✓"
    return 0;
}

pre_release_checks() {
    # TBD: use git signed git tags? Requires all team committers to setup GPG keys.
    # pre_release_check "You must create a git tag first!" \
    # 		      git tag -v "${PROJ_VERSION}"
    pre_release_check \
	"Local git tag \"${RELEASE_TAG}\" \
         does not exist for ${PROJ_FQNAME}" \
	git rev-parse --verify --quiet "${PROJ_VERSION}"
    pre_release_check \
    	"All tests must pass!" clojure -A:datomic-free:test
    return 0;
}

make_release_jar () {
    local proj_dir=$1
    local jar_name=$2
    local jar_dir="${proj_dir}/target"
    mkdir -p "${jar_dir}"
    clj -Spom
    run_step "Preparing release jar" \
	     clj -A:datomic-free:depstar -m hf.depstar.uberjar "${jar_dir}/${jar_name}" \
	     &> "${LOGFILE}"
}

PROJ_ROOT="$(git rev-parse --show-toplevel)"
LOGFILE="${PROJ_ROOT}/bundle-release.log"
RELEASE_TAG="$(git describe --abbrev=0)"
if [ -z "${RELEASE_TAG}" ]; then
    echo "ERROR: No git tag could be found!"
    exit 1
fi

PROJ_NAME="$(proj_name)"
PROJ_VERSION="$(proj_version)"

if [ -z "$PROJ_NAME" ] || [ -z "$PROJ_VERSION" ]; then
    echo "[ ❌ ] Could not determine Clojure project name and version."
    exit 2
fi

BUILD_DIR="target"
PROJ_FQNAME="${PROJ_NAME}-${RELEASE_TAG}"
LOG_SORT_SCRIPT="scripts/sort-edn-log.sh"
JAR_NAME="${PROJ_NAME}-${RELEASE_TAG}-standalone.jar"
DEPLOY_JAR="${BUILD_DIR}/${PROJ_FQNAME}/${PROJ_NAME}-${RELEASE_TAG}.jar"
RELEASE_DIR="${PROJ_ROOT}/release-archives"
RELEASE_ARCHIVE="${RELEASE_DIR}/${PROJ_NAME}-${RELEASE_TAG}.tar.xz"

EXEC_CMD=$(cat <<-EOF
Example:
clojure -Sdeps \
'{:deps {com.datomic/datomic-pro {:mvn/version "0.9.5703"}
         pseudoace {:local/root "target/pseudoace-0.6.0-SNAPSHOT/pseudoace-0.6.0-SNAPSHOT.jar"}}}' \
-m pseudoace.cli
EOF
)

cd "${PROJ_ROOT}"
pre_release_checks
mkdir -p "${BUILD_DIR}/${PROJ_FQNAME}"
mkdir -p "${RELEASE_DIR}"
release_file "${RELEASE_TAG}" "${LOG_SORT_SCRIPT}" "${BUILD_DIR}/${PROJ_FQNAME}/$(basename ${LOG_SORT_SCRIPT})"
make_release_jar "${PWD}" "${JAR_NAME}"
mv "$(find ./target -name ${JAR_NAME})" "${DEPLOY_JAR}"
cd "${BUILD_DIR}"
run_step "Creating release archive: ${RELEASE_ARCHIVE}" \
	 tar cpJf "${RELEASE_ARCHIVE}" "${PROJ_FQNAME}"
cd "${PROJ_DIR}"
rm -rf "${BUILD_DIR}"

if [ $? -eq 0 ]; then
    inform "Release archive usage:"
    echo "	-> Copy ${RELEASE_ARCHIVE} to the target machine"
    echo "	-> unpack with: tar xf <path-to-archive>"
    echo "	-> Run using:"
    echo "         ${EXEC_CMD}"
else
    echo "Darn, Something went wrong. please run with $SHELL -x --debug"
    exit 999
fi
