#!/usr/bin/env bash
set -e
set -x 
scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

username="elastic"
password=$(scbk get secret community-build-es-elastic-user -o go-template='{{.data.elastic | base64decode}}')

scbk exec community-build-es-default-0 -- \
  curl -k --user ${username}:${password} \
  -X PUT "https://localhost:9200/project-build-summary?pretty" \
  -H 'Content-Type: application/json'  -d "$(cat ${scriptDir}/../kibana/mappings/projectBuildSummary.json)"

