#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

username="elastic"
password=$(scbk get secret community-build-es-elastic-user -o go-template='{{.data.elastic | base64decode}}')

curl -k --user $username:$password -X POST "https://localhost:5601/api/saved_objects/_import?overwrite=true" -H "kbn-xsrf: true" --form file=@$scriptDir/../kibana/export.ndjson

