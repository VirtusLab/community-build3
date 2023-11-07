#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

scbk delete -f $scriptDir/../k8s/mvn-repo.yaml
scbk delete -f $scriptDir/../k8s/mvn-repo-data.yaml
scbk delete secret mvn-repo-passwords
scbk delete secret mvn-repo-keystore
