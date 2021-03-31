#!/bin/bash

docker rm fetch-sbt
docker build -t fetch-sbt .
docker run --add-host repo1.maven.org:$(ipconfig getifaddr en0) --add-host repo.maven.apache.org:$(ipconfig getifaddr en0) --add-host repo1.maven.org.fake:$(ipconfig getifaddr en0) -it --name fetch-sbt -p 5005:5005 --network builds-network fetch-sbt

