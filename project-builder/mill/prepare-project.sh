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

MILL_1_0="1.0.0"
MILL_0_12="0.12.14"
MILL_0_11=0.11.12
MILL_0_10=0.10.15
MILL_0_9=0.9.12
RESOLVE="resolve _"
MILL_BUILD_SCALA=build.mill.scala
MILL_BUILD=build.mill

cd $repoDir
millBuildFile=
for rootBuildFile in "$MILL_BUILD" "$MILL_BUILD_SCALA" "./build.sc"; do
  if [[ -f $rootBuildFile ]]; then
    millBuildFile=$rootBuildFile
    if [[ $rootBuildFile == "$MILL_BUILD_SCALA" ]]; then
      echo "Replace $MILL_BUILD_SCALA with $MILL_BUILD"
      mv $MILL_BUILD_SCALA $MILL_BUILD
      millBuildFile="$MILL_BUILD"
    fi
    break 1
  fi
done
if [[ -z "$millBuildFile" ]]; then
  echo "Not found a valid mill build root file"
  exit 1
fi

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
    for v in $MILL_1_0 $MILL_0_12 $MILL_0_11 $MILL_0_10 $MILL_0_9; do
      if `${scriptDir}/millw --mill-version $v $RESOLVE > /dev/null 2>/dev/null`; then
        echo "Successfully applied build using mill $v"
        millVersion=$v
        break
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
if [[ "$millBinaryVersionMajor" -eq "0" && ( "$millBinaryVersionMinor" -lt "9" || "$millBinaryVersionMinor" -gt "12" ) ]]; then 
  echo "Unsupported mill version"
  exit 1
fi

if [[ "$millBinaryVersionMajor" -gt "1" ]]; then 
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
adaptedFiles=( $millBuildFile )
millBuildDirectory=
# if [[ -d "./mill-build" ]]; then
#   millBuildDirectory="./mill-build"
if [[ -d "./project" ]]; then
   millBuildDirectory="./project"
fi
if [[ -z "$millBuildDirectory" ]]; then
  echo "No mill build directory found"
else
  for file in `find $millBuildDirectory -type f -name "*.mill.scala"`; do
    echo "Strip .scala suffix from $file"
    mv $file "${file%.scala}"
  done
  adaptedFiles+=(`find $millBuildDirectory -type f -name "*.mill"`)
  adaptedFiles+=(`find $millBuildDirectory -type f -name "*.sc"`)
fi
for buildFile in "${adaptedFiles[@]}"; do 
  isMainBuildFile=false
  if [[ "$buildFile" == "$millBuildFile" ]]; then
    isMainBuildFile=true
  fi
  echo "Apply scalafix rules to $buildFile"
  scalaFile="${buildFile}.scala"
  cp $buildFile $scalaFile
  scalafix \
    --rules file:${scriptDir}/scalafix/rules/src/main/scala/fix/Scala3CommunityBuildMillAdapter.scala \
    --files $scalaFile \
    --stdout \
    --syntactic \
    --settings.Scala3CommunityBuildMillAdapter.targetScalaVersion "$scalaVersion" \
    --settings.Scala3CommunityBuildMillAdapter.millBinaryVersion "$millBinaryVersion" \
    --settings.Scala3CommunityBuildMillAdapter.isMainBuildFile "$isMainBuildFile" \
    --scala-version 3.1.0 > ${buildFile}.adapted \
    && mv ${buildFile}.adapted $buildFile \
    || (echo "Failed to adapt $buildFile, ignoring changes"; cat ${buildFile}.adapted; rm -f ${buildFile}.adapted) 
  rm $scalaFile
done

# For portability between MacOS and Linux
relpath() {
    from=$(cd "$1" && pwd -P)   # -P: follow symlinks, get physical path
    to=$(cd "$2" && pwd -P)

    IFS='/' read -ra fparts <<< "$from"
    IFS='/' read -ra tparts <<< "$to"

    # Find common prefix length
    common_idx=0
    for ((i=0; i<${#fparts[@]} && i<${#tparts[@]}; i++)); do
        [[ "${fparts[i]}" == "${tparts[i]}" ]] || break
        ((common_idx++))
    done

    # How many “..” needed
    up=$(( ${#fparts[@]} - common_idx ))
    for ((i=0; i<up; i++)); do
        rel+=('../')
    done

    # Descend into the remainder of TO
    for ((i=common_idx; i<${#tparts[@]}; i++)); do
        rel+="${tparts[i]}"
        [[ $i -lt $(( ${#tparts[@]} - 1 )) ]] && rel+='/'
    done

    printf '%s\n' "${rel:-.}"
}

if [[ millBinaryVersionMajor -eq 0 ]]; then
  for f in "${adaptedFiles[@]}"; do
    dir="$(dirname $(realpath "$f"))"
    extension="${f##*.}"
    cp $scriptDir/MillCommunityBuild.sc ${dir}/MillCommunityBuild.$extension
    cp $scriptDir/compat/$millBinaryVersion.sc ${dir}/MillVersionCompat.$extension

    if [[ "$extension" == "sc" ]]; then
      # Compat for Mill 0.11+ sources
      cp $scriptDir/../shared/CommunityBuildCore.scala ${dir}/CommunityBuildCore.$extension
      for fileCopy in ${dir}/CommunityBuildCore.$extension ${dir}/MillCommunityBuild.$extension ${dir}/MillVersionCompat.sc; do
        scala-cli $scriptDir/../shared/searchAndReplace.scala -- $fileCopy "package build\n" "" 2> /dev/null
        scala-cli $scriptDir/../shared/searchAndReplace.scala -- $fileCopy "import CommunityBuildCore." "import \$file.CommunityBuildCore, CommunityBuildCore." 2> /dev/null
        scala-cli $scriptDir/../shared/searchAndReplace.scala -- $fileCopy "import MillVersionCompat." "import \$file.MillVersionCompat, MillVersionCompat." 2> /dev/null
      done
    else
      # Compat for Mill 0.12+ sources
      dirPackage="$(relpath "" "$dir")"
      dirPackage="${dirPackage/#\./}"          # drop leading "."  ( -> "" or "project/foo")
      dirPackage="${dirPackage//\//.}"         # "/" → "."         ( -> "" or "project.foo")
      pkg="build"
      if [[ -n "$dirPackage" ]]; then
        pkg+=".${dirPackage}"
      fi
      echo "package $pkg" | cat - $scriptDir/../shared/CommunityBuildCore.scala > ${dir}/CommunityBuildCore.$extension 
      for fileCopy in ${dir}/MillCommunityBuild.$extension ${dir}/MillVersionCompat.$extension; do
        scala-cli $scriptDir/../shared/searchAndReplace.scala -- $fileCopy "package build" "package $pkg" 2> /dev/null
      done
    fi

  done
elif [[ millBinaryVersionMajor -eq 1 ]]; then 
    dir="."
    cp $scriptDir/MillCommunityBuild.mill $dir/MillCommunityBuild.mill
    echo "package build" | cat - $scriptDir/../shared/CommunityBuildCore.scala > ${dir}/CommunityBuildCore.mill 
else
  echo "Unsupport mill binary version $millBinaryVersion"
  exit 1
fi
