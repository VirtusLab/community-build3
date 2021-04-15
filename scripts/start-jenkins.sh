#!/usr/bin/env bash
set -e

docker run \
  --name jenkins \
  -d \
  --rm \
  --env DOCKER_HOST=unix:///var/run/docker.sock \
  --env CASC_JENKINS_CONFIG=/var/jenkins_home/casc-configs \
  --network builds-network \
  -p 8080:8080 \
  -p 50000:50000 \
  -v jenkins-data:/var/jenkins_home \
  -v jenkins-docker-certs:/certs/client:ro \
  -v /var/run/docker.sock:/var/run/docker.sock \
communitybuild3/jenkins