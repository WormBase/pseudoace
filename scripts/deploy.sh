#!/bin/bash

LATEST_TAG=$(git describe --abbrev=0)
RELEASE_NAME="pseudoace-${LATEST_TAG}"
DEPLOY_JAR="target/${RELEASE_NAME}.jar"

rm -rf target
mkdir -p target
clj -Spom
clj -A:1.9:depstar -m hf.depstar.jar "${DEPLOY_JAR}"
mvn deploy
exit $?
