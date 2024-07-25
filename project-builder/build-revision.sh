#!/usr/bin/env bash
set -e

if [ $# -ne 10 ]; then
  echo "Wrong number of script arguments, got $# expected 10"
  exit 1
fi

function isBinVersionGreaterThen() {
    version1=$1
    version2=$2

    if [[ $version1 == $version2 ]]; then
        return 1
    fi

    IFS='.' read -ra v1 <<< "$version1"
    IFS='.' read -ra v2 <<< "$version2"

    # Compare major version
    if [[ ${v1[0]} -lt ${v2[0]} ]]; then
        return 1
    elif [[ ${v1[0]} -gt ${v2[0]} ]]; then
        return 0
    fi

    # Major versions are equal, compare minor version
    if [[ ${v1[1]} -lt ${v2[1]} ]]; then
        return 1
    elif [[ ${v1[1]} -gt ${v2[1]} ]]; then
        return 0
    fi
}

project="$1"
repoUrl="$2"            # e.g. 'https://github.com/Stiuil06/deploySbt.git'
rev="$3"                # e.g. '1.0.2'
scalaVersion="$4"       # e.g. 3.0.0-RC3
targets="$5"            # e.g. com.example%greeter
mvnRepoUrl="$6"         # e.g. https://mvn-repo/maven2/2021-05-23_1
projectConfig="$7"
extraScalacOptions="${8}" # e.g '' or "-Wunused:all -Ylightweight-lazy-vals"
disabledScalacOption="${9}"
extraLibraryDeps="${10}" # format org:artifact:version, eg. org.scala-lang:scala2-library-tasty_3:3.4.0-RC1

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
export OPENCB_SCRIPT_DIR=$scriptDir

$scriptDir/checkout.sh "$repoUrl" "$rev" repo
buildToolFile="build-tool.txt"

scalaBinaryVersion=`echo $scalaVersion | cut -d . -f 1,2`
scalaBinaryVersionMajor=`echo $scalaVersion | cut -d . -f 1`
scalaBinaryVersionMinor=`echo $scalaVersion | cut -d . -f 2`
echo "Scala binary version found: $scalaBinaryVersion"

sourceVersion=`echo $projectConfig | jq -r '.sourceVersion // ""'`
sourceVersionSetting=""

if [[ "$sourceVersion" =~ ^([0-9]+\.[0-9]+)(-migration)?$ ]]; then
  versionPart="${BASH_REMATCH[1]}"
  if isBinVersionGreaterThen "$versionPart" "$scalaBinaryVersion" ; then
    sourceVersionSetting="-source:$scalaBinaryVersion"
    echo "Explicit source version is great then used Scala version, it would be ignored"
  else 
    echo "Using configured source version: $sourceVersion"
    sourceVersionSetting="REQUIRE:-source:$sourceVersion"
  fi
else 
  echo "Configured version `$sourceVersion` is invalid, it would be ignored"
fi
if [[ -z "$sourceVersionSetting" ]]; then
  sourceVersion="$scalaBinaryVersion-migration"
  sourceVersionSetting="-source:$sourceVersion"
  echo "Implicitly using source version $sourceVersion"
fi

commonAppendScalacOptions="$sourceVersionSetting,-Wconf:msg=can be rewritten automatically under:s"
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

if [[ ! -z $extraLibraryDeps ]]; then
  echo "Would try to append extra library dependencies (best-effort, sbt/scala-cli only): ${extraLibraryDeps}"
fi

echo ""
echo "----"
echo "started" > build-status.txt 
if [ -f "repo/mill" ] || [ -f "repo/build.sc" ]; then
  echo "Mill project found: ${isMillProject}"
  echo "mill" > $buildToolFile
  $scriptDir/mill/prepare-project.sh "$project" repo "$scalaVersion" "$projectConfig" 
  $scriptDir/mill/build.sh repo "$scalaVersion" "$targets" "$mvnRepoUrl" "$projectConfig" "$extraScalacOptions" "$disabledScalacOption"

elif [ -f "repo/build.sbt" ]; then
  echo "sbt project found: ${isSbtProject}"
  echo "sbt" > $buildToolFile
  $scriptDir/sbt/prepare-project.sh "$project" repo "$scalaVersion" "$projectConfig"
  $scriptDir/sbt/build.sh repo "$scalaVersion" "$targets" "$mvnRepoUrl" "$projectConfig" "$extraScalacOptions" "$disabledScalacOption" "$extraLibraryDeps"
else
  echo "Not found sbt or mill build files, assuming scala-cli project"
  ls -l repo/
  echo "scala-cli" > $buildToolFile
  scala-cli config power true
  scala-cli bloop exit
  export COURSIER_REPOSITORIES="central|sonatype:releases|$mvnRepoUrl"
  scala-cli clean $scriptDir/scala-cli/
  scala-cli clean repo
  scala-cli $scriptDir/scala-cli/build.scala -- repo "$scalaVersion" "$projectConfig" "$mvnRepoUrl" "$extraLibraryDeps"
fi
