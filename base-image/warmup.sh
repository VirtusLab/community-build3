#!/usr/bin/env bash

set -e

scala_version=3.0.0-RC3 # TODO

mkdir warm_up

cd warm_up
mkdir project

echo 'scalaVersion := "'$scala_version'"' > build.sbt

echo '@main def run = println("Warmed up.")' > A.scala

sbt --sbt-version $SBT_VERSION --batch run
