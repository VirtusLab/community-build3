#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

kubectl apply -f https://download.elastic.co/downloads/eck/1.6.0/all-in-one.yaml

scbk apply -f k8s/elastic.yaml
