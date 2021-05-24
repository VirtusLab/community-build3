#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/start-maven.sh
$scriptDir/start-jenkins.sh
$scriptDir/start-elastic.sh
