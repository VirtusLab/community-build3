#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

shopt -s expand_aliases
source $scriptDir/env.sh

scbk apply -f $scriptDir/../k8s/maven-data.yaml

scbk apply -f $scriptDir/../k8s/maven.yaml
