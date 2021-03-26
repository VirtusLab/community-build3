## Get stats about versions and its deps from scaladex and maven central

It contains few main classes:


- `runDeps` will build a list of project (with over 20 stars for `3.0.0-RC1`) as well as its targets and dependencies (based on mvn central and scaladex). Results are cached in `data` directory
- `printBuildPlan` will run `runDeps` and will create build plan. It assumes that all deps follow semver and will not add and dependencies for non-latest version of each dependencies
- `runBuildPlan` will create build plan (in the same way how `printBuildPlan` does it) and build all locally. Each project in given version has its dedicated directory in `ws` dir where will have `repo` with git repository, `logs.txt` with logs and `res.txt`with results. Sbt command that will be run are printed to stdout. It will run build against `3.0.0-RC1-bin-SNAPSHOT` so to test latest version of compiler one needs to downgrade `baseVersion` in `project/Build.scala` in dotty repo.
- `resultSummary` will just gather results (it will not run buidld plan)

To run community build one need to have a version (preferably latest) relsed as `3.0.0-RC1-bin-SNAPSHOT`. To do so, clone dotty repository, update `baseVersion` in `project/Build.scala` ([this line](https://github.com/lampepfl/dotty/blob/master/project/Build.scala#L60)) to  `3.0.0-RC2` and run `sbt-dotty/scripted sbt-dotty/scaladoc-empty-test` (tests may fail).


## MVN Proxy
The spring-maven-repository directory is the fork of the [spring-maven-repository](https://github.com/Stiuil06/spring-maven-repository) repository. Copied for modifications needed by Dotty community builds mechanism.

# Run demo build:

Only once run:
`./buildBase.sh`

Then each time you 

`./run_maven_server_bg.sh`
