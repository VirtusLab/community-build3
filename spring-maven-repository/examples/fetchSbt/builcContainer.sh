#!/bin/zsh

docker build -t fetch-sbt .
docker run --add-host repo1.maven.org:$(ipconfig getifaddr en0) -it --name fetch-sbt -p 5005:5005 fetch-sbt