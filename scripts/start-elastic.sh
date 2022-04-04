#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

kubectl create -f https://download.elastic.co/downloads/eck/2.0.0/crds.yaml || true
kubectl apply -f https://download.elastic.co/downloads/eck/2.0.0/operator.yaml

scbk apply -f k8s/elastic.yaml

sleep 3
${scriptDir}/configure-elasticsearch.sh