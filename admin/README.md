# Admin

```docker
docker run -d -p 9093:8080 --rm \
-e JAVA_OPTS='-server -Xmx1g' \
-e PROFILE='default' \
-e SERVER_PORT=8080 \
registry.cn-qingdao.aliyuncs.com/upcwangying/admin:0.0.1-SNAPSHOT
```
