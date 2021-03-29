# mvn-repo with nginx-proxy

## Docker
`Also notice that the current configuration maps the proxy port (443) to your localhost port.`
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
1.
```
docker run --add-host repo1.maven.org:$(ipconfig getifaddr en0) --add-host repo.maven.apache.org:$(ipconfig getifaddr en0) --add-host repo1.maven.org.fake:$(ipconfig getifaddr en0) -it --name fetch-sbt -p 5005:5005 --network spring-maven-repository_builds-network fetch-sbt
```
2.
```
docker run --add-host repo1.maven.org:nginxProxyIp --add-host repo.maven.apache.org:nginxProxyIp --add-host repo1.maven.org.fake:nginxProxyIp -it --name fetch-sbt--network builds-network fetch-sbt
```
