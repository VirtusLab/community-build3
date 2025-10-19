#!/usr/bin/env bash

set -e

scala_version=3.7.3 # TODO

mkdir warm_up

cd warm_up
mkdir project

echo 'scalaVersion := "'$scala_version'"' > build.sbt

echo '@main def run = println("Warmed up.")' > A.scala

for version in $(echo $SBT_VERSIONS); do
  java -version
  sbt --sbt-version $version --batch run
done

cd ..
rm -rf warm_up