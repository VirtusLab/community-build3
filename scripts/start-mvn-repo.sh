#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

if [ -z "$MVN_REPO_KEYSTORE_PASSWORD" ];then
  export MVN_REPO_KEYSTORE_PASSWORD=$(openssl rand -base64 32)
fi

$scriptDir/generate-secrets.sh

scbk create secret generic mvn-repo-keystore --from-file=$scriptDir/../secrets/mvn-repo.p12 -o yaml --dry-run=client | kubectl apply -f -
scbk create secret generic mvn-repo-passwords --from-literal=keystore-password="$MVN_REPO_KEYSTORE_PASSWORD" -o yaml --dry-run=client | kubectl apply -f -
scbk create cm mvn-repo-cert --from-file=$scriptDir/../secrets/mvn-repo.crt -o yaml --dry-run=client | kubectl apply -f -
scbk apply -f $scriptDir/../k8s/mvn-repo-data.yaml
scbk apply -f $scriptDir/../k8s/mvn-repo.yaml
