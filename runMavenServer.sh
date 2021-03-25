#!/usr/bin/bash

set -e

cd spring-maven-repository

docker build  -t communitybuild3/maven . 
docker run -p 443:433 communitybuild3/maven