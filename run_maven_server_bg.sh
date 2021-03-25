#!/usr/bin/bash

set -e

cd spring-maven-repository

# Problem with redirects?
# docker build -t communitybuild3/maven . 
# docker run -p 8443:8433 communitybuild3/maven

./gradlew clean bootJar > ../server.logs 2>1

java -jar build/libs/$(ls build/libs/ | head -1)  >> ../server.logs &

jobs -p > ../server.pid