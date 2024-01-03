#!/usr/bin/env bash
set -e

if [ $# -ne 11 ]; then
  echo "Wrong number of script arguments, got $# expected 11"
  exit 1
fi

project="$1"
repoUrl="$2"            # e.g. 'https://github.com/Stiuil06/deploySbt.git'
rev="$3"                # e.g. '1.0.2'
scalaVersion="$4"       # e.g. 3.0.0-RC3
version="$5"            # e.g. '1.0.2-communityBuild'
targets="$6"            # e.g. com.example%greeter
mvnRepoUrl="$7"         # e.g. https://mvn-repo/maven2/2021-05-23_1
enforcedSbtVersion="$8" # e.g. '1.5.5' or empty ''
projectConfig="$9"
extraScalacOptions="${10}" # e.g '' or "-Wunused:all -Ylightweight-lazy-vals"
disabledScalacOption="${11}"

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
export OPENCB_SCRIPT_DIR=$scriptDir

$scriptDir/checkout.sh "$repoUrl" "$rev" repo
buildToolFile="build-tool.txt"

scalaBinaryVersion=`echo $scalaVersion | cut -d . -f 1,2`
scalaBinaryVersionMajor=`echo $scalaVersion | cut -d . -f 1`
scalaBinaryVersionMinor=`echo $scalaVersion | cut -d . -f 2`
echo "Scala binary version found: $scalaBinaryVersion"

commonAppendScalacOptions="-source:$scalaBinaryVersion-migration,-Wconf:msg=can be rewritten automatically under -rewrite -source $scalaBinaryVersion-migration:s"
commonRemoveScalacOptions="-deprecation,-feature,-Xfatal-warnings,-Werror,MATCH:.*-Wconf.*any:e,-migration,"
echo "Would try to apply common scalacOption (best-effort, sbt/mill only):"
echo "Append: $commonAppendScalacOptions"
echo "Remove: $commonRemoveScalacOptions"

if [ -z $extraScalacOptions ];then extraScalacOptions="$commonAppendScalacOptions"
else  extraScalacOptions="$extraScalacOptions,$commonAppendScalacOptions"
fi

if [ -z $disabledScalacOption ];then disabledScalacOption="$commonRemoveScalacOptions"
else disabledScalacOption="$disabledScalacOption,$commonRemoveScalacOptions"; 
fi

if [ -f "repo/mill" ] || [ -f "repo/build.sc" ]; then
  echo "Mill project found: ${isMillProject}"
  echo "mill" > $buildToolFile
  $scriptDir/mill/prepare-project.sh "$project" repo "$scalaVersion" "$version" "$projectConfig" 
  $scriptDir/mill/build.sh repo "$scalaVersion" "$version" "$targets" "$mvnRepoUrl" "$projectConfig" "$extraScalacOptions" "$disabledScalacOption"

elif [ -f "repo/build.sbt" ]; then
  echo "sbt project found: ${isSbtProject}"
  echo "sbt" > $buildToolFile
  $scriptDir/sbt/prepare-project.sh "$project" repo "$enforcedSbtVersion" "$scalaVersion" "$projectConfig"
  $scriptDir/sbt/build.sh repo "$scalaVersion" "$version" "$targets" "$mvnRepoUrl" "$projectConfig" "$extraScalacOptions" "$disabledScalacOption"
else
  echo "Not found sbt or mill build files, assuming scala-cli project"
  ls -l repo/
  echo "scala-cli" > $buildToolFile
  scala-cli clean $scriptDir/scala-cli/
  scala-cli clean repo
  scala-cli $scriptDir/scala-cli/build.scala -- repo "$scalaVersion" "$projectConfig" "$mvnRepoUrl"
fi
