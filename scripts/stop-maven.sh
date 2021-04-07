#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

docker-compose  -f $scriptDir/../spring-maven-repository/docker-compose.yml down -v
