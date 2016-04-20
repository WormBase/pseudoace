#!env bash
# -*- coding: utf-8 -*-

# Script for creating an archive of all the necessary artefact's
# for a given (git) tagged version.

PROJ_ROOT="$(git rev-parse --show-toplevel)"

proj_meta() {
    local grp="$1"
    local sin="$(head -n1 ${PROJ_ROOT}/project.clj)"
    read -r -d '' regexp  <<'END'
^\(defproject\s+(?P<clojars_group>\w+)/(?P<name>\w+)\s+\"(?P<version>.+)\"
END
    read -r -d '' src <<END
import re;
import sys;
print re.sub(*sys.argv[1:])
END
    python2 -c "${src}" "${regexp}" "\g<${grp}>" "$sin"
}

RELEASE_TAG="$1"
if [ -z "${RELEASE_TAG}" ]; then
    echo "USAGE: $0 <RELEASE_TAG> [LEIN_PROFILE]"
    exit 1
fi

LEIN_PROFILE="$2"
if [ -z "${LEIN_PROFILE}" ]; then
    lein="lein"
else
    lein="lein with-profile ${LEIN_PROFILE}"
fi

PROJ_NAME="$(proj_meta name)"
PROJ_VERSION="$(proj_meta version)"
if [ -z "$PROJ_NAME" ] || [ -z "$PROJ_VERSION" ]; then
    echo "[❌] Could not determine Clojure project name and version."
    exit 2
fi
BUILD_DIR="${PROJ_NAME}-$$"
PROJ_FQNAME="${PROJ_NAME}-${RELEASE_TAG}"
LOG_SORT_SCRIPT="scripts/sort-edn-log.sh"
DEPLOY_JAR="${BUILD_DIR}/${PROJ_FQNAME}/${PROJ_NAME}-${RELEASE_TAG}.jar"
RELEASE_DIR="${PROJ_ROOT}/release-archives"
RELEASE_ARCHIVE="${RELEASE_DIR}/${PROJ_NAME}-${RELEASE_TAG}.tar.gz"
ANNOT_MODELS_FILE="models/models.wrm.annot"

# output
WORM="🐛"
LABEL="[${WORM} ]"

run_step() {
    echo -n "${LABEL} $1 ... "
    shift
    $@ || echo "[❌]"; exit 1
    echo "[✓]"
}

release_file() {
    local release="$1"
    local source_path="$2"
    local target_path="$3"
    git show "${release}:${source_path}" > "${target_path}"
    chmod u+x "${target_path}"
}

pre_release_check() {
    local msg="$1"
    shift
    inform "Running pre-release check: $* in ${PWD}"
    $*
    if [ $? -ne 0 ]; then
        echo "ERROR: ${msg}"
        exit 1
    fi
    echo "${WORM}"
    return 0;
}

pre_release_checks() {
    local run_lein_check="lein with-profile test,${LEIN_PROFILE}"
    # TBD: use git signed git tags? Requires all team committers to setup GPG keys.
    # pre_release_check "You must create a git tag first!" \
    # 		      git tag -v "${PROJ_VERSION}"
    pre_release_check \
	"Local git tag \"${RELEASE_TAG}\" does not exist for ${PROJ_FQNAME}" \
	git rev-parse --verify --quiet "${PROJ_VERSION}"
    # pre_release_check \
    # 	"Git tag ${PROJ_VERSION} has not been pushed to the remote origin" \
    # 	git ls-remote --exit-code --tags origin "${PROJ_VERSION}"
    pre_release_check \
    	"All linting tests must pass!" "${run_lein_check}" eastwood
    pre_release_check \
    	"All tests must pass!" "${run_lein_check}" test
    return 0;
}

divider() {
    local msg="$1"
    let msg_len=${#msg}
    printf '_%.0s' $(seq 1 ${msg_len})
    echo
}

inform() {
    local msg="${LABEL} $1"
    echo "${msg}"
}

cd "${PROJ_ROOT}"
lein clean
pre_release_checks
inform "Pre-release checks passed"

inform "Prepare release files"
mkdir -p "${BUILD_DIR}/${PROJ_FQNAME}"
mkdir -p "${RELEASE_DIR}"
release_file "${RELEASE_TAG}" \
	     "${LOG_SORT_SCRIPT}" \
	     "${BUILD_DIR}/${PROJ_FQNAME}/$(basename ${LOG_SORT_SCRIPT})"
run_step "Preparing release jar" \
	 "$lein" do clean, uberjar
mv "${PROJ_ROOT}/target/${PROJ_NAME}-${RELEASE_TAG}-standalone.jar" \
   "${DEPLOY_JAR}"
cd "${BUILD_DIR}"
echo -n
run_step "Creating release archive: ${RELEASE_ARCHIVE}" \
	 tar jcpf "${RELEASE_ARCHIVE}" "${PROJ_FQNAME}"
cd -
run_step "Removing build directory ${BUILD_DIR} and everything under it" \
	 rm -rf "${BUILD_DIR}"

if [ $? -eq 0 ]; then
    inform "$RELEASE_ARCHIVE is located in $RELEASE_DIR."
    inform "Release archive usage:"
    echo "	-> Copy this to the target machine"
    echo "	-> unpack with: tar xf <path-to-archive>"
    echo "	-> Run using: java -jar ${DEPLOY_JAR}"
    echo -n " -> Ensure you are using latest version of java!"
    echo "(check  with: java -version)"
    # "lein test" will fail unless one cleans after uberjar'ing
    run_step "Cleaning up" "$lein" clean
    inform "${WORM} Goodybe! ${WORM}"
else
    echo "Darn, Something went wrong. please run with $SHELL -x --debug"
    exit 999
fi
