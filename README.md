### About
Hikari, A proxy tool which help you break through network restrictions.

### Usage
1. Install Java8, JDK or JRE is all fine;
2. Download the server or client jar file;
3. Run command: ```java -jar /path/to/jar_file/xxx.jar /path/to/config_file/xxx.json```;
4. Enjoy;

### Configuration
* Server Configuration:
  * listenAddress: listening address, your server internet address;
  * listenPortList: related port;
  * privateKeyList: allowed user keys;
  * bufferSize: size of data buffer, keep default 2048 please;
  * encryptType: which encrypt type to use, must be same with server;
  * secret: the key when encrypting;

* Client Configuration:
  * listenAddress: listen address, often 'localhost' or '127.0.0.1';
  * listenPort: related port;
  * localDnsResolve: 'true': using local DNS resolving; 'false': using server DNS resolving;
  * bufferSize: size of data buffer, keep default 2048 please;
  * encryptType: which encrypt type to use, must be same with server;
  * secret: the key when encrypting;
  * serverAddress: the server listening address, IP address or Hostname;
  * serverPortList: the server listening port;
  * privateKey: user key;
  
### Encrypt Type
supported encrypt type currently:

  * aes
  * rc4
  * plain(no encrypt)

### Other
recommend JVM arguments: ```-XX:+UseG1GC -XX:NewRatio=2 -XX:SurvivorRatio=6```
