# **HuaDi Microservices Cloud Platform**

![Maven Central](https://img.shields.io/maven-central/v/com.hdmcp/hdmcp)
![GitHub](https://img.shields.io/github/license/qdhuadi/hdmcp)

## 项目依赖

- Spring Boot (2.1.15.RELEASE)
- Spring Cloud (Greenwich.SR6)
- Spring Cloud Alibaba (2.1.2.RELEASE)

## Seata

- AT: 注解只在事务最开始发起方添加即可, 框架可以通过链路将事务上下文传递下去并加入事务.
- MT: 就是TCC, 需要用户自己实现3个接口.
- 将所有TCC服务都发布成了dubbo服务, 不需要 @LocalTCC 注解, @LocalTCC是在调用本机的TCC bean时才使用的.
- 需要根据一阶段的结果自己实现，抛异常分布式事务回滚，不抛异常分布式事务提交.
- TCC模式现在不支持与feign集成.
- AT模式二阶段是依赖数据资源的, 这个数据资源是指AT一阶段提交业务数据更新和相应回滚日志记录.
- AT模式必须基于支持本地 ACID 事务的关系型数据库、MT模式不依赖于底层数据资源的事务支持, 是从Seata框架的角度看的.
- Seata框架只针对资源, AT模式的资源是数据库, MT模式的资源是业务的接口.

根据两阶段行为模式的不同，我们将分支事务划分为 **Automatic (Branch) Transaction Mode** 和 **Manual (Branch) Transaction Mode**.

AT 模式基于支持**本地 ACID 事务**的**关系型数据库**:

- 一阶段 prepare 行为: 在本地事务中, 一并提交业务数据更新和相应回滚日志记录.
- 二阶段 commit 行为: 马上成功结束, **自动** 异步批量清理回滚日志.
- 二阶段 rollback 行为: 通过回滚日志, **自动** 生成补偿操作，完成数据回滚.

相应的, MT 模式不依赖于底层数据资源的事务支持:

- 一阶段 prepare 行为: 调用 **自定义** 的 prepare 逻辑.
- 二阶段 commit 行为: 调用 **自定义** 的 commit 逻辑.
- 二阶段 rollback 行为: 调用 **自定义** 的 rollback 逻辑.

所谓 MT 模式，是指支持把 **自定义** 的分支事务纳入到全局事务的管理中.

### 流程说明

![](process.png)

1. business通知server开启事务
2. business调用storage, storage开启本地事务, 本地事务提交时, 通知server创建分支事务
3. order 和 account 同 storage
4. rpc调用完毕后, business通知server提交事务
5. 每个分支本地事务提交后, 会通知server创建分支事务, 当rpc调用完毕后, business通知server提交全局事务(这个business是不是根据全局事务发起者方法里有没有抛出异常来决定提交或者回滚全局事务)

## Maven dockerfile plugin

```text
docker login  -u yourusername -p yourpassword registry.cn-qingdao.aliyuncs.com

mvn package -DskipTests dockerfile:build dockerfile:push

mvn package -DskipTests dockerfile:build dockerfile:push -Dusername=wangy -Dpassword=Wangy123 
```

## Docker compose

- 单机模式下运行

在后台运行

```docker
docker-compose up -d
```

or 指定文件名, 默认是docker-compose.yml

```docker
docker-compose up -d -f filename
```

- swarm 集群模式运行

TODO...

- k8s 集群模式运行

TODO...

## 测试用例

测试用例在hdmcp/product/product-web/src/test目录下

注意

1. 执行 `mvn test` 启动测试用例
2. 执行 `mvn package` `mvn install` `mvn deploy`时跳过 `mvn test`
3. 建议使用git子模块管理项目

### Test
```text
DskipTests 不执行测试用例, 但编译测试用例类生成相应的class文件至target/test-classes下

Dmaven.test.skip=true, 不执行测试用例，也不编译测试用例类
```

1. 使用`mvn package -Dmaven.test.skip=true`, 不但跳过单元测试的运行, 也跳过测试代码的编译; 也可以在pom.xml文件中修改.

```mvn
<plugin>  
    <groupId>org.apache.maven.plugin</groupId>  
    <artifactId>maven-compiler-plugin</artifactId>  
    <version>2.1</version>  
    <configuration>  
        <skip>true</skip>  
    </configuration>  
</plugin>

<plugin>  
    <groupId>org.apache.maven.plugins</groupId>  
    <artifactId>maven-surefire-plugin</artifactId>  
    <version>2.5</version>  
    <configuration>  
        <skip>true</skip>  
    </configuration>  
</plugin> 
```

2. 使用`mvn package -DskipTests`跳过单元测试, 但是会继续编译; 也可以在pom.xml文件中修改.

```mvn
<plugin>  
    <groupId>org.apache.maven.plugins</groupId>  
    <artifactId>maven-surefire-plugin</artifactId>  
    <version>2.5</version>  
    <configuration>  
        <skipTests>true</skipTests>  
    </configuration>  
</plugin> 
```

## Maven archetype

1. 在项目根目录下运行`mvn archetype:create-from-project`

2. 生成archetype项目工程, 在`target/generated-sources/archetype`目录下

## Maven Archetype 配置


1. 安装`Maven Archetype Catalog`插件

2. 依次进入设置目录`Settings->Build,Execution,Deployment->Build Tools->Maven Archetype Catalogs`

3. 新增http://192.168.88.82:8081/repository/maven-snapshots/archetype-catalog.xml

4. 新建Maven工程, 勾选`Create from archetype`即可, 其余操作不变.

## Deploy

`mvn clean install deploy -P release -Dgpg.passphrase=yourpassword -Ddockerfile.skip=true -DskipTests`

`mvn clean install package -DskipTests -Ddockerfile.push.skip=true`

`mvn clean deploy -projects fundebug-java,fundebug-spring -P release -Dgpg.passphrase=yourpassword -Ddockerfile.skip=true -DskipTests`

`keytool -genkey -alias samples -keypass samples -keyalg RSA -keysize 2048 -validity 30 -keystore samples.jks -storepass samples`

## TODO

- [X] Netflix Eureka切换成Nacos Discovery
- [X] Spring Cloud Config切换成Nacos Config
- [X] 原gateway模块更改为zuul模块, 基于Netflix Zuul
- [X] 新增gateway模块, 基于Spring Cloud Gateway
- [X] Feign切换成Dubbo
- [ ] Netflix Hystrix切换成Alibaba Sentinel
- [ ] Samples bpm集成Activiti
- [ ] 集成分布式事务中间件Alibaba Seata
- [ ] Zipkin切换成Skywalking
- [ ] Feign切换成gRPC
