#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

shopt -s expand_aliases
source $scriptDir/env.sh

scbk delete -f $scriptDir/../k8s/mvn-repo.yaml
scbk delete cm mvn-repo-cert
scbk delete secret mvn-repo-passwords
scbk delete secret mvn-repo-keystore
