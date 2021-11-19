#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/env.sh

helm -n $CM_K8S_NAMESPACE delete jenkins

scbk delete configmap jenkins-build-configs
scbk delete configmap jenkins-common-lib-vars
scbk delete configmap jenkins-seed-jobs
