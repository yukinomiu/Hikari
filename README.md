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

* Client Configuration:
  * listenAddress: listen address, often 'localhost' or '127.0.0.1';
  * listenPort: related port;
  * localDnsResolve: 'true': using local DNS resolving; 'false': using server DNS resolving;
  * bufferSize: size of data buffer, keep default 2048 please;
  * serverAddress: the server listening address, IP address or Hostname;
  * serverPortList: the server listening port;
  * privateKey: user key;

### Other
recommend JVM arguments: ```-XX:+UseG1GC -XX:NewRatio=2 -XX:SurvivorRatio=6```
