#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
k8sAuth=$scriptDir/../k8s/auth


$scriptDir/start-jenkins-operator.sh

source $scriptDir/utils.sh
scbk apply -f $k8sAuth/githubOAuthSecret.yaml
scbk apply -f $k8sAuth/authz-matrix.yaml
