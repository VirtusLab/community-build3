#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

docker build -t communitybuild3/mvn-repo $scriptDir/../spring-maven-repository/mvn-repo
