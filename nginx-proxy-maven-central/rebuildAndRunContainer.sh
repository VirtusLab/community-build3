#!/bin/bash

docker rm nginx-proxy
docker build -t nginx-proxy .
docker run --name nginx-proxy nginx-proxy