#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

shopt -s expand_aliases
source $scriptDir/env.sh

username=$(scbk get secret jenkins-operator-credentials-build -o 'jsonpath={.data.user}' | base64 -d)
password=$(scbk get secret jenkins-operator-credentials-build -o 'jsonpath={.data.password}' | base64 -d)

echo "Jenkins username: $username"
echo "Jenkins password: $password"

