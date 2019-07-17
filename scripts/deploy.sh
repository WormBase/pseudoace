#!/bin/bash

LATEST_TAG=$(git describe --abbrev=0)
RELEASE_NAME="pseudoace-${LATEST_TAG}"
RELEASE_ARCHIVE="release-archives/${RELEASE_NAME}.tar.xz"
DEPLOY_JAR="${RELEASE_NAME}.jar"

if ! test -f "${RELEASE_ARCHIVE}"; then
   echo "ERROR: jar file not built; run 'make bundle-release' first."
   exit 1
fi

tar xf "${RELEASE_ARCHIVE}" "${RELEASE_NAME}/$DEPLOY_JAR"
clj -Spom
mvn deploy:deploy-file \
     -Dfile="${DEPLOY_JAR}" \
     -Drepository=clojars \
     -Durl=https://clojars.org/repo \
     -DpomFile=pom.xml
rm -rf "${RELEASE_NAME}"
exit $?
