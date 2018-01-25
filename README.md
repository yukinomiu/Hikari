### 使用方法
0. 确保java8已被正确安装；
1. 下载对应的jar文件和配置文件；
2.运行```java -jar /path/to/jar_file/xxx.jar /path/to/config_file/xxx.json```；

### 配置文件说明
1. 服务端配置：
  * listenAddress：服务端监听端口
  * listenPortList：服务端监听端口
  * bufferSize：缓冲区大小，请保持默认
  * privateKeyList：允许的密码列表

2. 客户端配置：
  * listenAddress：客户端监听地址，一般请填写“localhost”或者“127.0.0.1”；
  * listenPort：客户端监听端口；
  * bufferSize：缓冲区大小，请保持默认；
  * localDnsResolve：是否开启本地DNS解析，true：本地DNS解析；false：服务端DNS解析
  * serverAddress：服务端地址
  * serverPortList：服务端端口列表
  * privateKey：密码

### 推荐JVM参数
-XX:+UseG1GC  -XX:NewRatio=2 -XX:SurvivorRatio=6
