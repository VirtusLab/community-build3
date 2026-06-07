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
export OPENCB_SCALA_VERSION=$scalaVersion

# Check if using a sbt with a supported version
javaVersion=$( echo "${projectConfig}" | jq -r '.java.version // "17"')
MinSbtVersion="1.11.5"

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

if [[ "$sbtMajor" -lt 2 ]]; then
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
else
  echo "Using sbt 2.x project adapter for sbt version $sbtVersion"
fi

prepareScript="${OPENCB_SCRIPT_DIR:?OPENCB_SCRIPT_DIR not defined}/prepare-scripts/${projectName}"
if [[ -f "$prepareScript" ]]; then
  if [[ -x "$prepareScript" ]]; then 
    echo "Execute project prepare script: ${prepareScript}"
    cat $prepareScript
    "$prepareScript"
  else echo "Project prepare script is not executable: $prepareScript"
  fi
else 
  echo "No prepare script found for project $projectName"
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
sharedDir="$scriptDir/shared"
if [[ "$sbtMajor" -ge 2 ]]; then
  adapterDir="$scriptDir/sbt2"
else
  adapterDir="$scriptDir/sbt1"
fi

ln -fs $scriptDir/../shared/CommunityBuildCore.scala $repoDir/project/CommunityBuildCore.scala
ln -fs $sharedDir/CommunityBuildConfigFormats.scala $repoDir/project/CommunityBuildConfigFormats.scala
ln -fs $sharedDir/CommunityBuildPluginShared.scala $repoDir/project/CommunityBuildPluginShared.scala
ln -fs $adapterDir/SbtTaskEvaluator.scala $repoDir/project/SbtTaskEvaluator.scala
ln -fs $adapterDir/SbtAdapterSupport.scala $repoDir/project/SbtAdapterSupport.scala
ln -fs $adapterDir/CommunityBuildPlugin.scala $repoDir/project/CommunityBuildPlugin.scala
if [[ "$sbtMajor" -ge 2 ]]; then
  ln -fs $adapterDir/CommunityBuildTestSupport.scala $repoDir/project/CommunityBuildTestSupport.scala
fi

# Register utility commands, for more info check command impl comments
echo -e "\ncommands ++= CommunityBuildPlugin.commands" >>$repoDir/build.sbt
# Add custom repositories 
echo -e '\nGlobal / resolvers += "The Scala Nightly Repository".at("https://repo.scala-lang.org/artifactory/maven-nightlies/")' >>$repoDir/build.sbt
# Ensure eviction errors are not failing the build
echo -e "\nThisBuild / evictionErrorLevel := sbt.util.Level.Warn" >>$repoDir/build.sbt
echo -e "\nThisBuild / evictionErrorLevel := sbt.util.Level.Warn" >>$repoDir/project/plugins.sbt


if [ -z "${OPENCB_AKKA_REPO_TOKEN:-}" ]; then
  echo "Warning: OPENCB_AKKA_REPO_TOKEN environment variable not set, skipping Akka secure repository configuration"
else
  echo -e '
ThisBuild / resolvers += "akka-secure-mvn" at "https://repo.akka.io/AKKA_REPO_TOKEN/secure/"
' | sed "s/AKKA_REPO_TOKEN/$OPENCB_AKKA_REPO_TOKEN/" >> $repoDir/project/akka.sbt
fi

# Project dependencies
# https://github.com/shiftleftsecurity/codepropertygraph#building-the-code
cd $repoDir
git lfs pull || true
