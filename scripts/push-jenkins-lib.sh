#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

echo "${BASH_SOURCE[0]}"
echo $scriptDir

shopt -s expand_aliases
source $scriptDir/env.sh

cd $scriptDir/../jenkins/common-lib && \
rm -rf .git && \
git init && \
git add --all && \
git commit -m "init" && \
scbk cp ../common-lib -c jenkins-master jenkins-build:/tmp && \
rm -rf .git
