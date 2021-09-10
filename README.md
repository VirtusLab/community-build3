# Scala 3 community build

## General architecture

Everything in run inside k8s.
The entire build is coordinated by jenkins, which dynamically spawns workers to do the following tasks:
* compute the build plan
* build scala compiler
* build scala community projects in proper order so that all the dependencies of a project are built before the project itself
(there is a limit of how many projects can be built within some reasonable time
and using only the resources we have for this purpose)

The artifacts of the compiler and the community projects are published to and pulled from a custom maven repository.
If a required artifact is not found here (e.g. java dependencies, scala dependencies which did not get into the build plan) normal resolvers of a particular project are used.
The repository is virtually divided into subrepositories for each run of the entire community build
so artifacts from different runs are not mixed with each other.

Elasticsearch with Kibana are used for aggregating and visualising build results.

## Limitations

Currently only sbt projects are supported.

## MVN repository
The spring-maven-repository directory is the fork of the [spring-maven-repository](https://github.com/Stiuil06/spring-maven-repository) repository. Copied for modifications needed by Dotty community builds mechanism.

## Local development

### Prerequisites

The steps below assume you have kubectl and minikube installed on your machine.
Because of a bug breaking DNS in Alpine Linux you might need to use a specific version of minikube (doesn't work with 1.22.0 or 1.21.0, works with 1.19.0).
Running the entire build with full infrastructure
(maven repository, jenkins master, jenkins workers, elasticsearch with kibana)
requires quite a lot of resources, which need to be declared while starting minikube
(tested successfully with 18GB of memory and 3 CPU cores on Mac; using hyperkit because the default driver caused problems).

```shell
minikube start --driver=hyperkit --memory=18432 --cpus=3
```

### Preparing docker images

Set the environment variables to publish docker images to minikube instead of docker directly

```shell
eval $(minikube -p minikube docker-env)
```

Most likely you'll need to build the base image only once (this will take quite a lot of time):

```shell
scripts/generate-secrets.sh
scripts/build-docker-base.sh
```

Build all the remaining images

```shell
scripts/build-quick.sh
```

or (re)build each image separately e.g.

```shell
scripts/build-maven.sh
```

### Debugging in k8s

The entire build infrastructure in k8s is defined inside one namespace. Running

```shell
source scripts/env.sh
```

will prepare your current shell session to work with these k8s resources. 
Among others this will set up `scbk` alias (standing for `Scala Communit Build K8S`)
working just as `kubectl` with the proper namespace set.

There are a couple of utility scripts to manage the lificycles of particular pieces of the infrastructure

```shell
scripts/start-XXX.sh
scripts/stop-XXX.sh
scripts/clean-XXX.sh
```

For convenience you can also run

```shell
scripts/start-all.sh
```

To be able to access the resources through your browser or send requests to them manually
you'll need to forward their ports using `scripts/forward-XXX.sh` scripts.
Each script needs to be run in a separate console and stopping the script will stop forwarding.
If a pod gets restarted the corresponding forwarding script needs to be stopped and started again.

Some resources might require credentials to be accessed via the UI.
Run `scripts/show-XXX-credentials.sh` to get them.

Useful k8s commands:

```shell
scbk get pods
scbk describe pod $POD_NAME
scbk logs $POD_NAME
scbk exec -it $POD_NAME -- sh
docker image ls | grep community | awk '{print $1":"$2}' | xargs docker save -o /tmp/community-build-images.tar
docker image load -i /tmp/community-build-images.tar
docker image ls | grep community | awk '{print $1":"$2}' | xargs docker image rm
scbk get pods --no-headers | grep daily- | awk '{print $1}' | xargs kubectl delete pod -n scala3-community-build
scbk run -i --tty executor-test --image=communitybuild3/executor image-pull-policy=Never -- sh
```

### Maven repository

UI URL:

```
http://localhost:8081/maven2
```

You can manage the published artifacts by logging into the repository pod with

```shell
scbk exec -it svc/mvn-repo -- bash
```

and modifying the content of `upload-dir` folder.

### Elasticsearch and Kibana

UI URL (your browser might complain about the page not being secure - proceed anyway):

```
https://localhost:5601/
```

You can load Kibana settings with `scripts/configure-kibana.sh`

If you want to create an index pattern for `communnity-build` manually, navigate to `(Burger menu in the left upper corner) -> Stack Management -> Index patterns -> Create index pattern` (you won't be able to create an index manually unless there are already some data for it).

Elasticsearch and Kibana currently don't use persistent storage so every restart will clean all the data.

### Jenkins

UI URL: 

```
http://localhost:8080
```

Jenkins is set up by [Jenkins Operator](https://jenkinsci.github.io/kubernetes-operator/).

Jenkins requires the maven repo and elasticsearch to be up and running to successfully execute the entire build flow.

To trigger a new run of the community build, start the `runDaily` job.
When this job finishes navigate to `/daily/${currentDate}` to see the build progress of particular projects.

For development purposes only you can uncomment the backup section in `k8s/jenkins.yaml`
to make jenkins preserve data of jobs after a restart (remember to build the backup image first).
This might however not be very reliable.
You can get into the backup container for debugging with

```
scbk exec -it jenkins-build -c backup -- bash
```

If jenkins doesn't get set up properly or keeps crashing and restarting
you can try to debug that by looking at the logs of the operator pod.

A know issue is that jenkins itself might not start if it decides that you should update some of the plugins.
You should then bump the versions in the yaml config.

For easier development of shared jenkins libraries you can use `scripts/push-jenkins-lib.sh`
to upload the locally modified files without having to restart jenkins.

### Accelerating development and testing in jenkins

As the entire flow is quite time consuming you might find it more convenient to skip some parts of it to test some of your changes, e.g.

* Compute the build plan only once locally and pass it as a parameter of the build when triggering the daily build

* Use a manually prepared build plan inluding only a few repositories whose builds are very fast. You can use the ones included in this project (in `sample-repos` directory) for this purpose.
First set up a pod hosting the repositories as shown below and then use the build plan from `sample-repos/buildPlan.json`
```shell
eval $(minikube -p minikube docker-env)
scripts/build-sample-repos.sh
scripts/start-sample-repos.sh
```

* Use a version of the compiler which is already published (e.g. `3.0.0`) - this will skip the local build

### Building a project locally

Assuming you have the maven repo running in k8s, you can try to build a locally cloned project using the already published dependencies.

```shell
# Verify support for project's build tool and inject the plugin file(s). This needs to be run only once per cloned repo. ENFORCED_SBT_VERSION may be set to an empty string 
executor/prepare-project.sh $PROJECT_PATH $ENFORCED_SBT_VERSION

# Build the project using the build script or directly with sbt
executor/build.sh $PROJECT_PATH $SCALA_VERSION $PROJECT_VERSION "$TARGETS" $MVN_REPO_URL
# e.g.
# executor/build.sh tmp/shapeless '3.0.1-RC1-bin-COMMUNITY-SNAPSHOT' '3.0.0-M4' 'org.typelevel%shapeless3-deriving org.typelevel%shapeless3-data org.typelevel%shapeless3-test org.typelevel%shapeless3-typeable' 'http://localhost:8081/maven2/2021-06-02_2'
```

Warning: you might run into problems if you manually rebuild only some of the artifacts using a different version of JDK that the one used for building the rest.

### Running simple test demo

This will only build and publish the latest scala compiler and a single community project.
This doesn't use jenkins or elasticsearch.

Assuming you have started minikube (you might set less resources than for the full build),
built the docker images as described above and started the maven repository run

```shell
scripts/test-build.sh
```

## Build coordinator (outdated)

It contains few main classes:


- `runDeps` will build a list of project (with over 20 stars for `3.0.0-RC3`) as well as its targets and dependencies (based on mvn central and scaladex). Results are cached in `data` directory
- `printBuildPlan` will run `runDeps` and will create build plan. It assumes that all deps follow semver and will not add and dependencies for non-latest version of each dependencies
- `runBuildPlan` will create build plan (in the same way how `printBuildPlan` does it) and build all locally. Each project in given version has its dedicated directory in `ws` dir where will have `repo` with git repository, `logs.txt` with logs and `res.txt`with results. Sbt command that will be run are printed to stdout. It will run build against `3.0.0-RC3-bin-SNAPSHOT` so to test latest version of compiler one needs to downgrade `baseVersion` in `project/Build.scala` in dotty repo.
- `resultSummary` will just gather results (it will not run buidld plan)

## Docker development tips (outdated)

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
