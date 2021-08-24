#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

shopt -s expand_aliases
source $scriptDir/env.sh

scbk delete -f $scriptDir/../k8s/jenkins.yaml
scbk delete -f https://raw.githubusercontent.com/jenkinsci/kubernetes-operator/v0.6.0/deploy/all-in-one-v1alpha2.yaml
scbk delete configmap jenkins-casc-configs
scbk delete configmap jenkins-init-scripts
scbk delete configmap jenkins-common-lib-vars
scbk delete configmap jenkins-seed-jobs
