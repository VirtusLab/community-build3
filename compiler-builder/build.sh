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


PATH="/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH"
java -version
sbt --batch \
  \;'set every publishTo := Some("Community Build Repo" at "'$CB_MVN_REPO_URL'")'  \
  \;'set every version := "'$scalaVersion'"' \
  \;'show scala3-bootstrapped/publishTo' \
  \;"scala3-bootstrapped/publish"
