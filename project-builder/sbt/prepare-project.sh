#!/usr/bin/env bash

set -e

if [ $# -ne 4 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

projectName="$1"
repoDir="$2"            # e.g. /tmp/shapeless
scalaVersion="$3"
projectConfig="$4"

export OPENCB_PROJECT_DIR=$repoDir

# Check if using a sbt with a supported version
javaVersion=$( echo "${projectConfig}" | jq -r '.java.version // "17"')
MinSbtVersion="1.10.0"

buildPropsFile="${repoDir}/project/build.properties"
if [ ! -f "${buildPropsFile}" ]; then
  echo "'project/build.properties' is missing"
  mkdir ${repoDir}/project || true
  echo "sbt.version=${MinSbtVersion}" >$buildPropsFile
fi

pluginsFile="${repoDir}/project/plugins.sbt"
scalafixConf="${repoDir}/.scalafix.conf"
if [[ -f "${scalafixConf}" || `grep 'scalafix' $pluginsFile` ]]; then 
  # Force minimal scalafix version (to handle Scala 3 nightly version parsing)
  echo -e '\naddSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.2")' >> $pluginsFile
fi

sbtVersion=$(cat "${buildPropsFile}" | grep sbt.version | awk -F= '{ print $2 }')

function parseSemver() {
  local prefixSufix=($(echo ${1/-/ }))
  local prefix=${prefixSufix[0]}
  local suffix=${prefixSufix[1]}
  local numberParts=($(echo ${prefix//./ }))
  local major=${numberParts[0]}
  local minor=${numberParts[1]}
  local patch=${numberParts[2]}
  echo "$major $minor $patch $suffix"
}

sbtSemVerParts=($(echo $(parseSemver "$sbtVersion")))
sbtMajor=${sbtSemVerParts[0]}
sbtMinor=${sbtSemVerParts[1]}
sbtPatch=${sbtSemVerParts[2]}

minSbtSemVerParts=($(echo $(parseSemver "$MinSbtVersion")))
minSbtMajor=${minSbtSemVerParts[0]}
minSbtMinor=${minSbtSemVerParts[1]}
minSbtPatch=${minSbtSemVerParts[2]}

if [[ "$sbtMajor" -lt "$minSbtMajor" ]] ||
  ([[ "$sbtMajor" -eq "$minSbtMajor" ]] && [[ "$sbtMinor" -lt "$minSbtMinor" ]]) ||
  ([[ "$sbtMajor" -eq "$minSbtMajor" ]] && [[ "$sbtMinor" -eq "$minSbtMinor" ]] && [[ "$sbtPatch" -lt "$minSbtPatch" ]]); then
  echo "Sbt version $sbtVersion is not supported, minimal supported version is $MinSbtVersion"
  echo "Enforcing usage of sbt in version ${MinSbtVersion}"
  sed -i -E "s/(sbt.version\s*=\s*).*/\1${MinSbtVersion}/" "${buildPropsFile}" || echo "sbt.version=$MinSbtVersion" > "${buildPropsFile}"
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"


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
  scala-cli run $scriptDir/../shared/searchAndReplace.scala -- "${repoDir}/${path}" "${pattern}" "${replaceWith}"
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

ln -fs $scriptDir/../shared/CommunityBuildCore.scala $repoDir/project/CommunityBuildCore.scala
ln -fs $scriptDir/CommunityBuildPlugin.scala $repoDir/project/CommunityBuildPlugin.scala

# Register utility commands, for more info check command impl comments
echo -e "\ncommands ++= CommunityBuildPlugin.commands" >>$repoDir/build.sbt
# Ensure eviction errors are not failing the build
echo -e "\nThisBuild / evictionErrorLevel := Level.Warn" >>$repoDir/build.sbt
echo -e "\nThisBuild / evictionErrorLevel := Level.Warn" >>$repoDir/project/plugins.sbt

# Project dependencies
# https://github.com/shiftleftsecurity/codepropertygraph#building-the-code
cd $repoDir
git lfs pull || true
## scala-debug adapter
# Skip if no .ssh key provided
(echo "StrictHostKeyChecking no" >> ~/.ssh/config) || true
(git submodule sync && git submodule update --init --recursive) || true
