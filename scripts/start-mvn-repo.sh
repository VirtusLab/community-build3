#!/usr/bin/env bash
set -e

if [ -z "$MVN_REPO_KEYSTORE_PASSWORD" ]; then
  echo "MVN_REPO_KEYSTORE_PASSWORD env variable has to be set and nonempty."
  echo "(Make sure it's set to the value used when running scripts/generate-secrets.sh)"
  exit 1
fi

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

scbk create secret generic mvn-repo-keystore --from-file=$scriptDir/../secrets/mvn-repo.p12
scbk create secret generic mvn-repo-passwords --from-literal=keystore-password="$MVN_REPO_KEYSTORE_PASSWORD"
scbk create cm mvn-repo-cert --from-file=$scriptDir/../secrets/mvn-repo.crt
scbk apply -f $scriptDir/../k8s/mvn-repo-data.yaml
scbk apply -f $scriptDir/../k8s/mvn-repo.yaml
