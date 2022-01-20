#!/usr/bin/env bash
set -e 

if [ $# -ne 6 ]; then 
  echo "Wrong number of script arguments"
  exit 1
fi

scalaVersion="$1" # e.g. 3.0.0
minStarsCount="$2" # e.g. 100
maxProjectsCount="$3" # e.g. 50, negative number for no limit
requiredProjects="$4" # e.g "typelevel/cats,scalaz/scalaz"
replacedProjectsConfigPath="$5" # e.g. /tmp/replaced-projects.txt 
projectsConfigPath="$6" # e.g. /tmp/projects-config.conf 

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

cd $scriptDir && sbt "runMain storeDependenciesBasedBuildPlan \"$scalaVersion\" \"$minStarsCount\" \"$maxProjectsCount\" \"$requiredProjects\" \"$replacedProjectsConfigPath\" \"$projectsConfigPath\""
