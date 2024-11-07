#!/usr/bin/env bash
set -e

if [ $# -ne 3 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repoDir="$1" # e.g. /tmp/dotty
scalaVersion="$2" # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
export CB_MVN_REPO_URL="$3" # e.g. https://mvn-repo/maven2/2021-05-23_1

echo '##################################'
echo "Release Scala in version: $scalaVersion"
echo "Maven repo at: $CB_MVN_REPO_URL"
echo '##################################'

cd "$repoDir"

compilerVersion="$(sbt --error 'print scala3-compiler-bootstrapped/version' | head -n 1 | xargs)"
if [[ "$scalaVersion" != "$compilerVersion" ]]; then 
  echo "Configured version $scalaVersion does not match compiler version $compilerVersion"
  exit 1
fi

sbt --batch \
  \;'set every sonatypePublishToBundle := Some("Community Build Repo" at sys.env("CB_MVN_REPO_URL"))'  \
  \;"scala3-bootstrapped/publish"
