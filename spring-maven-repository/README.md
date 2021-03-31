# mvn-repo with nginx-proxy

## Docker
Note that the current configuration maps the proxy port (443) to your localhost port. In the future, it should not be visible outside the virtual docker network.

### docker-compose usage
`docker-compose up`  - run mvn-repo and nginx-proxy
Useful flags:  
-d, --detach               Detached mode: Run containers in the background,
print new container names.  

`docker-compose stop` - stop containers

## networks
### backend-network
Allows visibility between `mvn-repo` and `nginx-proxy`.

### builds-network
Allows `nginx-proxy` visibility.  
Your builds should be running on this network to see the proxy.  
Add `--network builds-network` flag to `docker run` command or
```dockerfile
    networks:
      builds-network:
```
to your service in `docker-compose.yml`.  
If you run with docker-compose the network may have some prefix, check it with `docker network ls`.  
Example: `spring-maven-repository_builds-network`.

## example build run
Example build project `examples/fetchSbt` - look at Dockerfile there. The most important thing is to update java (to latest build of necessary version, not latest version) and copy certificates.
1.
```
docker run \
--add-host repo1.maven.org:$(ipconfig getifaddr en0) \
--add-host repo.maven.apache.org:$(ipconfig getifaddr en0) \
--add-host repo1.maven.org.fake:$(ipconfig getifaddr en0) \
-it --name fetch-sbt \
-p 5005:5005 \
--network builds-network \
fetch-sbt
```
2.
```
docker run \
--add-host repo1.maven.org:nginxProxyIp \
--add-host repo.maven.apache.org:nginxProxyIp \
--add-host repo1.maven.org.fake:nginxProxyIp \
-it --name fetch-sbt--network builds-network \
fetch-sbt
```
P.S. run these commands as oneliners
