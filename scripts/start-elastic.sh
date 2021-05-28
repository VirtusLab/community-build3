#!/usr/bin/env bash
set -e

docker run \
  --name elasticsearch \
  -d \
  --net builds-network \
  -p 9200:9200 \
  -p 9300:9300 \
  -e "discovery.type=single-node" \
  --memory="1g" \
  docker.elastic.co/elasticsearch/elasticsearch:7.12.1

docker run \
  --name kibana \
  -d \
  --net builds-network \
  -p 5601:5601 \
  --memory="1g" \
  -e "ELASTICSEARCH_HOSTS=http://elasticsearch:9200" \
  docker.elastic.co/kibana/kibana:7.12.1
