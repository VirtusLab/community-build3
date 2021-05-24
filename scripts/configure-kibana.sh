#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

shopt -s expand_aliases
source $scriptDir/env.sh

username="elastic"
password=$(scbk get secret community-build-es-elastic-user -o go-template='{{.data.elastic | base64decode}}')

curl -k --user $username:$password -X POST "https://localhost:5601/api/saved_objects/_import?createNewCopies=true" -H "kbn-xsrf: true" --form file=@$scriptDir/../kibana/export.ndjson

