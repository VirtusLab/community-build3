#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

echo "${BASH_SOURCE[0]}"
echo $scriptDir

shopt -s expand_aliases
source $scriptDir/env.sh

scbk port-forward jenkins-build 8080:8080
