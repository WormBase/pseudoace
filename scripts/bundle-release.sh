#!env bash
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
    local lein_profiles="test,${LEIN_PROFILE}"
    local run_lein_check="lein with-profile ${lein_profiles}"
    # TBD: use git signed git tags? Requires all team committers to setup GPG keys.
    # pre_release_check "You must create a git tag first!" \
    # 		      git tag -v "${PROJ_VERSION}"
    pre_release_check \
	"Local git tag \"${RELEASE_TAG}\" \
         does not exist for ${PROJ_FQNAME}" \
	git rev-parse --verify --quiet "${PROJ_VERSION}"
    pre_release_check \
    	"All linting tests must pass!" "${run_lein_check}" eastwood
    pre_release_check \
    	"All tests must pass!" "${run_lein_check}" test
    return 0;
}

make_release_jar () {
    local lein_profiles="${LEIN_PROFILE}"
    run_step "Preparing release jar" \
	     "lein with-profile ${lein_profiles}" \
	     do clean, uberjar &> "${LOGFILE}"
}

PROJ_ROOT="$(git rev-parse --show-toplevel)"
LOGFILE="${PROJ_ROOT}/bundle-release.log"
RELEASE_TAG="$1"
if [ -z "${RELEASE_TAG}" ]; then
    echo "USAGE: $0 <RELEASE_TAG> [LEIN_PROFILE]"
    exit 1
fi

LEIN_PROFILE="$2"
PROJ_NAME="$(proj_meta ${PROJ_ROOT} name)"
PROJ_VERSION="$(proj_meta ${PROJ_ROOT} version)"

if [ -z "$PROJ_NAME" ] || [ -z "$PROJ_VERSION" ]; then
    echo "[ ❌ ] Could not determine Clojure project name and version."
    exit 2
fi

BUILD_DIR="${PROJ_NAME}-$$"
PROJ_FQNAME="${PROJ_NAME}-${RELEASE_TAG}"
LOG_SORT_SCRIPT="scripts/sort-edn-log.sh"
DEPLOY_JAR="${BUILD_DIR}/${PROJ_FQNAME}/${PROJ_NAME}-${RELEASE_TAG}.jar"
RELEASE_DIR="${PROJ_ROOT}/release-archives"
RELEASE_ARCHIVE="${RELEASE_DIR}/${PROJ_NAME}-${RELEASE_TAG}.tar.gz"
ANNOT_MODELS_FILE="models/models.wrm.annot"

cd "${PROJ_ROOT}"
lein clean
pre_release_checks
mkdir -p "${BUILD_DIR}/${PROJ_FQNAME}"
mkdir -p "${RELEASE_DIR}"
release_file "${RELEASE_TAG}" \
	 "${LOG_SORT_SCRIPT}" \
	 "${BUILD_DIR}/${PROJ_FQNAME}/$(basename ${LOG_SORT_SCRIPT})"
make_release_jar

mv "$(find ./target -name ${PROJ_NAME}-${RELEASE_TAG}-standalone.jar)" \
   "${DEPLOY_JAR}"
cd "${BUILD_DIR}"
run_step "Creating release archive: ${RELEASE_ARCHIVE}" \
	 tar jcpf "${RELEASE_ARCHIVE}" "${PROJ_FQNAME}"
cd "${PROJ_DIR}"
rm -rf "${BUILD_DIR}"

if [ $? -eq 0 ]; then
    inform "Release archive usage:"
    echo "	-> Copy ${RELEASE_ARCHIVE} to the target machine"
    echo "	-> unpack with: tar xf <path-to-archive>"
    echo "	-> Run using: java -jar ${DEPLOY_JAR}"
    echo -n " -> Ensure you are using latest version of java!"
    echo "(check  with: java -version)"
else
    echo "Darn, Something went wrong. please run with $SHELL -x --debug"
    exit 999
fi
# "lein test" will fail unless one cleans after uberjar'ing
run_step "Cleaning up" "lein with-profile ${LEIN_PROFILE}" clean
