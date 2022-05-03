#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

if [ -z "$CB_DOCKER_USERNAME" ]; then
  echo >&2 "CB_DOCKER_USERNAME env variable has to be set"
  exit 1
fi

if [ -z "$CB_DOCKER_PASSWORD" ]; then
  echo >&2 "CB_DOCKER_PASSWORD env variable has to be set"
  exit 1
fi

if [ -z "$CB_K8S_NAMESPACE" ]; then
  echo >&2 "CB_K8S_NAMESPACE env variable has to be set"
  exit 1
fi

REMOTE_CREDS_SECRET=jenkins-remote-credentials
if [[ -z "${CB_BUILD_TOKEN+x}" ]]; then
  CB_BUILD_TOKEN="$(echo $RANDOM | md5sum | head -c 32)"
fi

HELM_EXPERIMENTAL_OCI=1 helm registry login operatorservice.azurecr.io -u "$CB_DOCKER_USERNAME" -p "$CB_DOCKER_PASSWORD"

scbk apply -f $scriptDir/../k8s/auth/authz-matrix.yaml 
scbk apply -f $scriptDir/../k8s/jenkins-priority.yaml
scbk create configmap jenkins-seed-jobs --from-file=$scriptDir/../jenkins/seeds --dry-run=client -o yaml | scbk apply -f -
scbk create configmap jenkins-common-lib-vars --from-file=$scriptDir/../jenkins/common-lib/vars --dry-run=client -o yaml | scbk apply -f -
scbk create configmap jenkins-build-configs --from-file=$scriptDir/../env/prod/config --dry-run=client -o yaml | scbk apply -f -
scbk create configmap jenkins-build-scripts --from-file=$scriptDir/../jenkins/scripts --dry-run=client -o yaml | scbk apply -f -
scbk get secret ${REMOTE_CREDS_SECRET} 2>/dev/null >/dev/null || \
  scbk create secret generic ${REMOTE_CREDS_SECRET} --from-literal=runbuild-token="${CB_BUILD_TOKEN}"

jenkinsClientId=$(scbk get secret/jenkins-github-oauth-secret -o 'jsonpath={.data.clientID}' | base64 -d)

# Make sure env ids starts from env count set in jenkins.yaml
HELM_EXPERIMENTAL_OCI=1 helm --namespace="$CB_K8S_NAMESPACE" \
  upgrade jenkins oci://operatorservice.azurecr.io/charts/op-svc-jenkins-crs --install --version 0.3.1 -f k8s/jenkins.yaml \
  --set 'jenkins.podSpec.jenkinsController.env[1].name'=BUILD_TOKEN \
  --set 'jenkins.podSpec.jenkinsController.env[1].valueFrom.secretKeyRef.name'="$REMOTE_CREDS_SECRET" \
  --set 'jenkins.podSpec.jenkinsController.env[1].valueFrom.secretKeyRef.key'="runbuild-token" \
  --set 'jenkinsAuthentication.githubOAuth.clientID'="${jenkinsClientId}" \
  --set 'jenkinsAuthentication.githubOAuth.clientSecretRef.namespace'="$CB_K8S_NAMESPACE"
