#!/bin/bash

proxyLocation=$(host $1 | awk '/has address/ { print $4 }')

echo "$proxyLocation    repo1.maven.org" >> /etc/hosts
echo "$proxyLocation    repo.maven.apache.org" >> /etc/hosts
echo "$proxyLocation    repo1.maven.org.fake" >> /etc/hosts

echo $proxyLocation
