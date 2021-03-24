# example sbt project compiled with Scala 3 - showing build process using special maven proxy

## Usage

This is a normal sbt project. You can compile code with `sbt compile`, run it with `sbt run`, and `sbt console` will start a Scala 3 REPL.

For more information on the sbt-dotty plugin, see the
[dotty-example-project](https://github.com/lampepfl/dotty-example-project/blob/master/README.md).


## Docker
### Run in container
Build image from fetchSbt directory:  
`docker build -t fetch-sbt .`  
Run image `fetch-sbt` as container with the same name `--name fetch-sbt`
`docker run --name fetch-sbt fetch-sbt`  
Interactive mode:  
`docker run --name fetch-sbt -it fetch-sbt`  
To replace maven central address with our mvn proxy ad flag:
`--add-host repo1.maven.org:mvnProxyIp`  

Example command with interactive mode, mapping mvn central to my host machinea and expose debug port (handy for debugging):  
`docker run --add-host repo1.maven.org:$(ipconfig getifaddr en0) -it --name fetch-sbt -p 5005:5005 fetch-sbt`


### Clean up
Remove container:  
`docker container rm fetch-sbt`  
  
Remove image: (note that it is possible to build images incrementally - without removing previous)  
`docker image rm fetch-sbt`

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