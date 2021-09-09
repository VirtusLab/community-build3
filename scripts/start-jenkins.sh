#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

shopt -s expand_aliases
source $scriptDir/env.sh

scbk apply -f $scriptDir/../k8s/jenkins-data.yaml

scbk create configmap jenkins-seed-jobs --from-file=$scriptDir/../jenkins/seeds --dry-run=client -o yaml | scbk apply -f -
scbk create configmap jenkins-common-lib-vars --from-file=$scriptDir/../jenkins/common-lib/vars --dry-run=client -o yaml | scbk apply -f -
scbk create configmap jenkins-init-scripts --from-file=$scriptDir/../jenkins/init-scripts --dry-run=client -o yaml | scbk apply -f -
scbk create configmap jenkins-casc-configs --from-file=$scriptDir/../jenkins/casc-configs --dry-run=client -o yaml | scbk apply -f -
scbk create configmap jenkins-build-configs --from-file=$scriptDir/../env/prod/config --dry-run=client -o yaml | scbk apply -f -
scbk apply -f https://raw.githubusercontent.com/jenkinsci/kubernetes-operator/v0.6.0/config/crd/bases/jenkins.io_jenkins.yaml
scbk apply -f https://raw.githubusercontent.com/jenkinsci/kubernetes-operator/v0.6.0/deploy/all-in-one-v1alpha2.yaml
scbk apply -f $scriptDir/../k8s/jenkins.yaml
