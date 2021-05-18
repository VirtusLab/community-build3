#!/usr/bin/env bash
set -e

docker stop elasticsearch
docker rm elasticsearch
docker stop kibana
docker rm kibana