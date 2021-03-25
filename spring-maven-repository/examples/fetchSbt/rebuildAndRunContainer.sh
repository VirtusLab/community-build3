#!/bin/bash

docker rm fetch-sbt
docker build -t fetch-sbt .
docker run --add-host repo1.maven.org:172.17.0.3 --add-host repo.maven.apache.org:172.17.0.3 --add-host repo1.maven.org.fake:172.17.0.2 -it --name fetch-sbt -p 5005:5005 fetch-sbt



