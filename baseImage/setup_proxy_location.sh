#!/bin/bash

proxyLocation=$(host $1 | awk '/has address/ { print $4 }')

echo "$proxyLocation    repo1.maven.org" >> /etc/hosts
echo "$proxyLocation    repo.maven.apache.org" >> /etc/hosts
echo "$proxyLocation    repo1.maven.org.fake" >> /etc/hosts

curl -k https://repo1.maven.org
curl -k https://repo.maven.apache.org
curl -k https://repo1.maven.org.fake
curl -k https://repo1.maven.org/maven2
curl -k https://repo.maven.apache.org/maven2
curl -k https://repo1.maven.org.fake/maven2

echo $proxyLocation
