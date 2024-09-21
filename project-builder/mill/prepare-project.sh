#!/usr/bin/env bash

set -e

if [ $# -ne 4 ]; then
  echo "Wrong number of script arguments, expected 3 $0 <repo_dir> <scala_version> <project_config>, got $#: $@"
  exit 1
fi

projectName="$1"
repoDir="$2" # e.g. /tmp/shapeless
scalaVersion="$3" # e.g. 3.1.2-RC1
projectConfig="$4" 

export OPENCB_PROJECT_DIR=$repoDir

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

MILL_0_12="0.12.0-RC2"
MILL_0_11=0.11.12
MILL_0_10=0.10.15
MILL_0_9=0.9.12
RESOLVE="resolve _"

cd $repoDir
millVersion=
if [[ -f .mill-version ]];then
  millVersion=`cat .mill-version`
  echo "Found explicit mill version $millVersion"
else
  echo "No .mill-version file found, detecting compatible mill version"
  if [[ -f ./mill ]];then
    echo "Found mill runner script, trying to resolve version"
    ./mill -v $RESOLVE || true
    millVersion=`./mill -v $RESOLVE | grep -E "Mill.*version" | grep -E -o "([0-9]+\.[0-9]+\.[0-9]+)" || echo ""`
    if [[ -z "$millVersion" ]]; then
      # AI suggested workaround for non-portable grep
      millVersion=`./mill -v $RESOLVE | awk '/Mill Build Tool version/ { for (i=1; i<=NF; i++) if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+$/) print $i }' || echo ""`
    fi
  fi
  if [[ "$millVersion" == "" ]]; then
    echo "Trying one of predefiend mill versions"
    for v in $MILL_0_12 $MILL_0_11 $MILL_0_10 $MILL_0_9; do
      if `${scriptDir}/millw --mill-version $v $RESOLVE > /dev/null 2>/dev/null`; then
        echo "Successfully applied build using mill $v"
        buildScVersions=($MILL_0_11 $MILL_0_10 $MILL_0_9)
        if [[ " ${buildScVersions[*]} " =~ " ${v} " && -f ./build.sc ]]; then
          echo "Found build.sc file and matching Mill version" 
          millVersion=$v
          break
        elif [[ -f ./build.mill ]]; then
          echo "Found build.mill file"
          millVersion=$v
          break
        else
          echo "Able to resolve build, but not found matching build files"
        fi
      else 
        echo "Failed to apply build using mill $v"
      fi
    done 
  fi
  if [[ -z "$millVersion" ]];then
    echo "Failed to resolve compatible mill version, abort"
    exit 1
  else
    # Force found version in build
    echo $millVersion > .mill-version
  fi
fi # detect version

millBinaryVersion=`echo $millVersion | cut -d . -f 1,2`
echo "Detected mill version=$millVersion, binary version: $millBinaryVersion"
millBinaryVersionMajor=`echo $millVersion | cut -d . -f 1`
millBinaryVersionMinor=`echo $millVersion | cut -d . -f 2`
# 0.9 is the minimal verified supported version
# 0.13 does not exit yet
if [[ "$millBinaryVersionMajor" -ne "0" || ( "$millBinaryVersionMinor" -lt "9" || "$millBinaryVersionMinor" -gt "12" ) ]]; then 
  echo "Unsupported mill version"
  exit 1
fi

# Base64 is used to mitigate spliting json by whitespaces
for elem in $(echo "${projectConfig}" | jq -r '.sourcePatches // [] | .[] | @base64'); do
  function field() {
    echo ${elem} | base64 --decode | jq -r ${1}
  }
  replaceWith=$(echo "$(field '.replaceWith')" | sed "s/<SCALA_VERSION>/${scalaVersion}/")
  path=$(field '.path')
  pattern=$(field '.pattern')
  echo "Try apply source patch:"
  echo "Path:        $path"
  echo "Pattern:     $pattern"
  echo "Replacement: $replaceWith"
  scala-cli run $scriptDir/../shared/searchAndReplace.scala -- "${path}" "${pattern}" "${replaceWith}"
done

prepareScript="${OPENCB_SCRIPT_DIR:?OPENCB_SCRIPT_DIR not defined}/prepare-scripts/${projectName}.sh"
if [[ -f "$prepareScript" ]]; then
  if [[ -x "$prepareScript" ]]; then 
    echo "Execute project prepare script: ${prepareScript}"
    cat $prepareScript
    bash "$prepareScript"
  else echo "Project prepare script is not executable: $prepareScript"
  fi
else 
  echo "No prepare script found for project $projectName"
fi

# Rename build.sc to build.scala - Scalafix does ignore .sc files
# Use scala 3 dialect to allow for top level defs
millBuildExt="mill"
if [[ "$millBinaryVersionMajor" -eq "0" && "$millBinaryVersionMinor" -le "11" ]]; then 
  millBuildExt=sc
fi
adaptedFiles=( $PWD/build.${millBuildExt} )
if [[ -d ./project ]]; then
  adaptedFiles+=(`find ./project -type f -name "*.$millBuildExt"`)
fi
for buildFile in "${adaptedFiles[@]}"; do 
  echo "Apply scalafix rules to $buildFile"
  scalaFile="${buildFile%.$millBuildExt}.scala"
  cp $buildFile $scalaFile
  scalafix \
    --rules file:${scriptDir}/scalafix/rules/src/main/scala/fix/Scala3CommunityBuildMillAdapter.scala \
    --files $scalaFile \
    --stdout \
    --syntactic \
    --settings.Scala3CommunityBuildMillAdapter.targetScalaVersion "$scalaVersion" \
    --settings.Scala3CommunityBuildMillAdapter.millBinaryVersion "$millBinaryVersion" \
    --scala-version 3.1.0 > ${buildFile}.adapted \
    && mv ${buildFile}.adapted $buildFile \
    || (echo "Failed to adapt $buildFile, ignoring changes"; cat ${buildFile}.adapted; rm -f ${buildFile}.adapted) 
  rm $scalaFile
done

for f in "${adaptedFiles[@]}"; do
  dir="$(dirname $(realpath "$f"))"
  cp $scriptDir/MillCommunityBuild.sc ${dir}/MillCommunityBuild.$millBuildExt
  cp $scriptDir/compat/$millBinaryVersion.sc ${dir}/MillVersionCompat.$millBuildExt

  if [[ "$millBinaryVersionMajor" -gt "0" || "$millBinaryVersionMinor" -ge "12" ]]; then
    # Compat for Mill 0.12+ sources
    echo "package build" | cat - $scriptDir/../shared/CommunityBuildCore.scala > ${dir}/CommunityBuildCore.$millBuildExt
  else  
    # Compat for Mill 0.11+ sources
    cp $scriptDir/../shared/CommunityBuildCore.scala ${dir}/CommunityBuildCore.$millBuildExt
    for fileCopy in ${dir}/CommunityBuildCore.$millBuildExt ${dir}/MillCommunityBuild.$millBuildExt ${dir}/MillVersionCompat.$millBuildExt; do
      scala-cli $scriptDir/../shared/searchAndReplace.scala -- $fileCopy "package build\n" ""
      scala-cli $scriptDir/../shared/searchAndReplace.scala -- $fileCopy "import CommunityBuildCore." "import \$file.CommunityBuildCore, CommunityBuildCore."
      scala-cli $scriptDir/../shared/searchAndReplace.scala -- $fileCopy "import MillVersionCompat." "import \$file.MillVersionCompat, MillVersionCompat."
    done
  fi

done
