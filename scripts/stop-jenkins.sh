#!/usr/bin/env bash
set -e

docker stop jenkins
docker volume rm jenkins-data jenkins-docker-certs