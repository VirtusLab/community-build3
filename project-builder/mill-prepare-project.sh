#!/usr/bin/env bash

set -e

if [ $# -ne 3 ]; then
  echo "Wrong number of script arguments, expected 3 $0 <repo_dir> <scala_version> <publish_version>, got $#: $@"
  exit 1
fi

repoDir="$1" # e.g. /tmp/shapeless
scalaVersion="$2" # e.g. 3.1.2-RC1
publishVersion="$3" # version of the project

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

buildCodeInject=$(cat <<-END
import \$file.MillCommunityBuild
// Main entry point for community build
def runCommunityBuild(_evaluator: mill.eval.Evaluator, scalaVersion: String, targets: String*) = T.command {
  implicit val ctx = MillCommunityBuild.Ctx(this, scalaVersion, _evaluator, T.log)
  MillCommunityBuild.runBuild(targets)
}

// Replaces mill.define.Cross allowing to use map used cross versions
class MillCommunityBuildCross[T: _root_.scala.reflect.ClassTag]
  (cases: _root_.scala.Any*)
  (buildScalaVersion: _root_.java.lang.String)
  (implicit ci:  _root_.mill.define.Cross.Factory[T], ctx: _root_.mill.define.Ctx) 
  extends _root_.mill.define.Cross[T](
      MillCommunityBuild.mapCrossVersions(buildScalaVersion, cases): _*
    )
// End of code injects
END
)

# Inject Community Build code and override settings
# 1. Replace usage of Cross with custom CommunityBuildCross
# 2. Override scalaVersion in not cross build modules
# 3. Replace usage of PublishModule with CommunityBuildPublishModule
# 4. Set correct publishVersion in all published modules 
cp repo/build.sc repo/build.sc.bak \
  && (echo -e "${buildCodeInject}\n\n"; cat repo/build.sc.bak) \
  | sed -E "s/(extends|with) Cross(\[.+\]\(.*\))/\1 MillCommunityBuildCross\2(\"${scalaVersion}\")/" \
  | sed -E "s/(def scalaVersion[ :].*=)(.*)/\1 \"${scalaVersion}\"/" \
  | sed -E "s/(extends|with) PublishModule([ {])/\1 MillCommunityBuild.CommunityBuildPublishModule\2/" \
  | sed -E "s/(def publishVersion[ :].*=)(.*)/\1 \"${publishVersion}\"/" \
  > repo/build.sc 

ln -fs $scriptDir/MillCommunityBuild.sc $repoDir/MillCommunityBuild.sc
ln -fs $scriptDir/MillCommunityBuildModel.sc $repoDir/MillCommunityBuildModel.sc