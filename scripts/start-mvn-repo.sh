#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

export MVN_REPO_KEYSTORE_PASSWORD=$(openssl rand -base64 32)

$scriptDir/generate-secrets.sh

scbk create secret generic mvn-repo-keystore --from-file=$scriptDir/../secrets/mvn-repo.p12
scbk create secret generic mvn-repo-passwords --from-literal=keystore-password="$MVN_REPO_KEYSTORE_PASSWORD"
scbk create cm mvn-repo-cert --from-file=$scriptDir/../secrets/mvn-repo.crt
scbk apply -f $scriptDir/../k8s/mvn-repo-data.yaml
scbk apply -f $scriptDir/../k8s/mvn-repo.yaml
