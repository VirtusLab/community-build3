#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

secretsDir=$scriptDir/../secrets
mkdir -p $secretsDir
cd $secretsDir

# Generate and distribute SSL certificates

for hostAddr in repo.maven.apache.org repo1.maven.org repo1.maven.org.fake mvn-repo; do
  openssl req -newkey rsa:2048 -nodes -batch -subj "/CN=$hostAddr" -keyout $hostAddr.key -x509 -days 365 -out $hostAddr.crt
done

baseImageKeystoreDir=$scriptDir/../base-image/keystorage
mkdir -p $baseImageKeystoreDir
cp $secretsDir/*.crt $baseImageKeystoreDir

nginxProxyKeystoreDir=$scriptDir/../spring-maven-repository/nginx-proxy/keystore
mkdir -p $nginxProxyKeystoreDir
cp $secretsDir/*.crt $secretsDir/*.key $nginxProxyKeystoreDir
