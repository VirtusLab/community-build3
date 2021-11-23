#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

cd $scriptDir/../jenkins/common-lib && \
rm -rf .git && \
git init && \
git add --all && \
git commit -m "init" && \
scbk cp ../common-lib -c jenkins-controller jenkins-build:/tmp && \
rm -rf .git
