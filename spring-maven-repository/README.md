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