#!/usr/bin/env bash

set -e

scala_version=3.2.1 # TODO

mkdir warm_up

cd warm_up
mkdir project

echo 'scalaVersion := "'$scala_version'"' > build.sbt

echo '@main def run = println("Warmed up.")' > A.scala

for version in $(echo $SBT_VERSIONS); do
  sbt --sbt-version $version --batch run
done
