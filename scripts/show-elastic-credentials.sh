#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

shopt -s expand_aliases
source $scriptDir/env.sh

export ELASTIC_USERNAME="elastic"
export ELASTIC_PASSWORD=$(scbk get secret community-build-es-elastic-user -o go-template='{{.data.elastic | base64decode}}')

echo "Elastic username: $ELASTIC_USERNAME"
echo "Elastic password: $ELASTIC_PASSWORD"

