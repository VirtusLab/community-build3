# spring-maven-repository

Private Maven repositories written in Spring Framework. Based on repository [appengine-maven-repository](https://github.com/renaudcerrato/appengine-maven-repository).

## Usage
Run `gradle build && gradle bootRun` and that now you have running simple maven repository.
If you want test it, go to `examples/deploy` directory and run `gradle build && gradle uploadArchives` (you can adjust mvn repo localization in build.gradle file).
Then go to `examples/fetch` directory and run `gradle build && gradle run` (like in deploy case you can adjust mvn repo localization in build.gradle file)

## Proxy
If you want use this mvn repo as simple proxy you need to specify `maven.redirectUrl` properties with another maven repo url, eg. `https://repo.maven.apache.org/maven2`. 
It works something like that `if I have a file which you need I will give you it, if not I will redirect you to another mvn repo - 302 redirect`.


## MultiRepo
If you want to use this mvn repo as multi repository (many separate mvn repository) with proxy option, eg. for community builds with different versions of your app, you need to set `maven.multiRepo` properties (flag) on true.
Then you will be able to use mvn proxy repo with prefixes before artifact name, eg.  
instead  
https://youMavenRepoUrl.com/  
https://youMavenRepoUrl.com/com/example/greeter/1.0.0/greeter-1.0.0.pom  
use  
https://youMavenRepoUrl.com/repo1/  
https://youMavenRepoUrl.com/repo1/com/example/greeter/1.0.0/greeter-1.0.0.pom   
**note `repo1` part, it is prefix for repo, you can use different prefixes and you will get multi repositories**

## Requirements
Gradle >=6.7 and Java >=11

## Properties
`server.port` - changing port for repo server
`server.servlet.context-path` - url prefix (`/maven2` to imitate official maven repo)
`maven.multiRepo` - (default false) set true if you want use server in multi repositories mode
`maven.redirectUrl` - if set, redirection will work to another repo in case of dependency absence

## Dependency Mapping Feature
If proxy dont have exact version that client request, is looking similar version.  
Examples:  
1.0.1 match to 1.0.3, 1.0.12, etc  
1.0.1 not match to 1.1.3, 1.2.12, etc  
In [SemVer](https://semver.org/) context it is possible to match version with different version in 'patch' part.


## Info Feature
By adding empty `info` flag as query param in url you will get information about dependency and its mappings.  
Example when we have `greeter` library only in veresion `1.0.0`:  
`http://localhost:8080/com/example/greeter_3.0.0-RC1/1.0.0/greeter_3.0.0-RC1-1.0.0.jar?info`
```json
{
"organization": "com.example",
"name": "greeter",
"filename": "greeter_3.0.0-RC1-1.0.0.jar",
"scalaVersion": "3.0.0-RC1",
"version": "1.0.0",
"jarType": "",
"extension": "jar",
"mappedTo": null,
"dependencyPath": "com/example/greeter_3.0.0-RC1"
}
```
`http://localhost:8080/com/example/greeter_3.0.0-RC1/1.0.2/greeter_3.0.0-RC1-1.0.2.jar?info`
```json
{
"organization": "com.example",
"name": "greeter",
"filename": "greeter_3.0.0-RC1-1.0.2.jar",
"scalaVersion": "3.0.0-RC1",
"version": "1.0.2",
"jarType": "",
"extension": "jar",
"mappedTo": {
    "organization": "com.example",
    "name": "greeter",
    "filename": "greeter_3.0.0-RC1-1.0.0.jar",
    "scalaVersion": "3.0.0-RC1",
    "version": "1.0.0",
    "jarType": "",
    "extension": "jar",
    "mappedTo": null,
    "dependencyPath": "com/example/greeter_3.0.0-RC1"
},
"dependencyPath": "com/example/greeter_3.0.0-RC1"
}
```
`http://localhost:8080/com/example/greeter_3.0.0-RC1/1.0.2/greeter_3.0.0-RC1-1.0.2-sources.jar?info`
```json
{
"organization": "com.example",
"name": "greeter",
"filename": "greeter_3.0.0-RC1-1.0.2-sources.jar",
"scalaVersion": "3.0.0-RC1",
"version": "1.0.2",
"jarType": "sources",
"extension": "jar",
"mappedTo": {
    "organization": "com.example",
    "name": "greeter",
    "filename": "greeter_3.0.0-RC1-1.0.0-sources.jar",
    "scalaVersion": "3.0.0-RC1",
    "version": "1.0.0",
    "jarType": "sources",
    "extension": "jar",
    "mappedTo": null,
    "dependencyPath": "com/example/greeter_3.0.0-RC1"
},
"dependencyPath": "com/example/greeter_3.0.0-RC1"
}
```