#!/bin/bash

LATEST_TAG=$(git describe --abbrev=0)
RELEASE_NAME="pseudoace-${LATEST_TAG}"
DEPLOY_JAR="${RELEASE_NAME}.jar"
mkdir -p target
clj -Spom
clj -A:1.9:depstar -m hf.depstar.jar "target/${DEPLOY_JAR}"
mvn deploy:deploy-file \
     -Dfile="target/${DEPLOY_JAR}" \
     -Drepository=clojars \
     -Durl=https://clojars.org/repo \
     -DpomFile=pom.xml
rm -rf target
exit $?
