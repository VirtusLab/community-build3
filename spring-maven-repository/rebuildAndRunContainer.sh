#!/bin/bash

docker rm mvn-repo
docker build -t mvn-repo .
docker run --name mvn-repo mvn-repo