#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

if [ -z "$CB_K8S_NAMESPACE" ]; then
  echo >&2 "CB_K8S_NAMESPACE env variable has to be set"
  exit 1
fi

helm -n $CB_K8S_NAMESPACE delete jenkins

scbk delete configmap jenkins-build-configs
scbk delete configmap jenkins-build-scripts
scbk delete configmap jenkins-common-lib-vars
scbk delete configmap jenkins-seed-jobs
