#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

shopt -s expand_aliases
source $scriptDir/env.sh

scbk delete -f $scriptDir/../k8s/sample-repos.yaml
