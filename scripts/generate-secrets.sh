#!/usr/bin/env bash
set -e

if [ -z "$MVN_REPO_KEYSTORE_PASSWORD" ]; then
  echo "MVN_REPO_KEYSTORE_PASSWORD env variable has to be set and nonempty"
  exit 1
fi

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

secretsDir=$scriptDir/../secrets
mkdir -p $secretsDir
cd $secretsDir

# Generate and distribute SSL certificates

for hostAddr in mvn-repo; do
  openssl req -newkey rsa:2048 -nodes -batch -subj "/CN=$hostAddr" -keyout $hostAddr.key -x509 -days 365 -out $hostAddr.crt
  openssl pkcs12 -export -out $hostAddr.p12 -inkey $hostAddr.key -in $hostAddr.crt -name $hostAddr -password "pass:$MVN_REPO_KEYSTORE_PASSWORD"
done
