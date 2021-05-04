## Get stats about versions and its deps from scaladex and maven central

It contains few main classes:


- `runDeps` will build a list of project (with over 20 stars for `3.0.0-RC3`) as well as its targets and dependencies (based on mvn central and scaladex). Results are cached in `data` directory
- `printBuildPlan` will run `runDeps` and will create build plan. It assumes that all deps follow semver and will not add and dependencies for non-latest version of each dependencies
- `runBuildPlan` will create build plan (in the same way how `printBuildPlan` does it) and build all locally. Each project in given version has its dedicated directory in `ws` dir where will have `repo` with git repository, `logs.txt` with logs and `res.txt`with results. Sbt command that will be run are printed to stdout. It will run build against `3.0.0-RC3-bin-SNAPSHOT` so to test latest version of compiler one needs to downgrade `baseVersion` in `project/Build.scala` in dotty repo.
- `resultSummary` will just gather results (it will not run buidld plan)

To run community build one need to have a version (preferably latest) relsed as `3.0.0-RC3-bin-SNAPSHOT`. To do so, clone dotty repository, update `baseVersion` in `project/Build.scala` ([this line](https://github.com/lampepfl/dotty/blob/master/project/Build.scala#L60)) to  `3.0.0-RC3` and run `sbt-dotty/scripted sbt-dotty/scaladoc-empty-test` (tests may fail).


## MVN Proxy
The spring-maven-repository directory is the fork of the [spring-maven-repository](https://github.com/Stiuil06/spring-maven-repository) repository. Copied for modifications needed by Dotty community builds mechanism.

## Run demo build:

### Prerequisites

The steps below assume you have docker set up on your machine.
If working on MacOS make sure that the memory limit for docker containers is high enough
(the default 2GB is definitely too little as the build of scala3 repository has a limit of 4GB for the JVM - successfully tested with 8GB).

### Running demo

Only once run:

```
scripts/generate-secrets.sh
scripts/build-docker-base.sh
```

Then each time you 

`scripts/run-demo-build.sh`

You can also run particular steps from the script above manually for debugging.
In such a case you might want to build a local repository rather than a git repo.
For this purpose you can run `scripts/publish-local-project.sh` (currently this works for sbt projects only), e.g.

```
scripts/publish-local-project.sh spring-maven-repository/examples/deploySbt 1.0.2-communityBuild com.example%greeter
```

Remember that for this to work the local maven has to be running and the scala version used as the parameter has to be already published.

## Running community build in Jenkins in Docker

Assuming you have performed the preparation steps from the demo script

```
scripts/start-maven.sh
sleep 100
scripts/build-publish-scala.sh
scripts/build-executor.sh
scripts/build-coordinator.sh
```

you can start jenkins in docker with

```
scripts/build-jenkins.sh
scripts/start-jenkins.sh
```

Navigate to http://localhost:8080/ to access jenkins UI.
Start the `runDaily` job to trigger the community build.
To see the results, go to `/daily/${currentDate}`.
Next, to see the visualization of the build plan, click `Dependency graph` in the menu on the left and select `jsplumb` format.

Navigate to http://localhost:8081/maven2/ to see the content of our local maven repository.

When working on Mac you might need to increase the memory limit for docker even more as jenkins has 2 workers by default, which might build projects in parallel (you might need to try with 10~16 GB although this hasn't been measured exactly yet).

## General development tips

### Debug app in container
To debug an application in a container, you need to set JAVA_TOOL_OPTIONS env variable (in container) and expose pointed in it port. How you do this, depends on your work environment.

#### by Dockerfile
(Note that it doesnt map a port to your host)
```dockerfile
ENV JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,address=0.0.0.0:5005,server=y,suspend=n
EXPOSE 5005
```

#### by docker run

Add `-p 5005:5005` to expose and map port 5005 to your host machine.  
Add `--env JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,address=0.0.0.0:5005,server=y,suspend=n` flag to setup debug listening.

### Debug app IDE setup
This configuration is dedicated to VScode with Metals.  
`launch.json`
```json
{
  "version": "0.2.0",
    "configurations": [
        {
            "type": "scala",
            "name": "Attach to docker",
            "request": "attach",
            "hostName": "localhost",
            "port": 5005,
            "buildTarget": "root"
        }
    ]
}
```
hostName - `localhost` if debug port is mapped to host, else IP address of container.
port - port on which JVM listen for debug - the same like in `JAVA_TOOL_OPTIONS` env variable