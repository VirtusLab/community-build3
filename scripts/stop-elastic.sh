#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

scbk delete -f $scriptDir/../k8s/elastic.yaml
kubectl delete -f https://download.elastic.co/downloads/eck/2.9.0/crds.yaml 
kubectl delete -f https://download.elastic.co/downloads/eck/2.9.0/operator.yaml
