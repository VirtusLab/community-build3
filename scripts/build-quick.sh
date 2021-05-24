#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/build-coordinator.sh
$scriptDir/build-publish-scala.sh
$scriptDir/build-executor.sh
$scriptDir/build-maven.sh
$scriptDir/build-jenkins-backup.sh
