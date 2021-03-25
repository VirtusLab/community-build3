#!/usr/bin/bash

set -e

scala_version=3.0.0-RC1 # TODO

mkdir warm_up

echo 'addSbtPlugin("ch.epfl.lamp" % "sbt-dotty" % "0.5.3")' > "zzzz_dotty.sbt"

cd warm_up
mkdir project

echo 'scalaVersion := "'$scala_version'"' > build.sbt
cp ../zzzz_dotty.sbt project/zzzz_dotty.sbt

echo '@main def run = println("Warmed up.")' > A.scala

sbt --sbt-version 1.4.8 --batch run
