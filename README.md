### About
Hikari, A proxy tool which help you break through network restrictions.

The Hikari client listen on a local socket port and wait for incoming SOCKS4 or SOCKS5 requests,
when incoming SOCKS requests arrived, the client process the request, and try to connect to Hikari server
using Hikari protocol. When a connection between Hikari client and Hikari server established
the client side response the SOCKS request and is ready for proxy.

Process:

> local app <---[plain data]---> Hikari client <---[Hikari protocol(encrypted)]---> Hikari server <---[plain data]---> target app

### Usage
1. Install Java(version 8 or newer);
2. Download the server or client executable jar file and configuration file;
3. Run command: ```java -jar /path/to/jar_file/xxx.jar /path/to/config_file/xxx.json```;
4. Enjoy;

### Configuration
* Server Configuration:
  * listenAddress: listening address;
  * listenPortList: related port;
  * privateKeyList: the list of allowed user keys;
  * bufferSize: size of data buffer, keep default 2048 please;
  * encryptType: encryption type, must be same with server;
  * secret: the key when encrypting, must be same with server;

* Client Configuration:
  * listenAddress: listening address, often 'localhost' or '127.0.0.1';
  * listenPort: related port;
  * localDnsResolve: 'true': using local DNS resolving; 'false': using remote(server) DNS resolving;
  * bufferSize: size of data buffer, keep default 2048 please;
  * encryptType: encryption type, must be same with server;
  * secret: the key when encrypting, must be same with server;
  * serverAddress: the server listening address, IP address or Hostname;
  * serverPortList: the server listening port;
  * privateKey: user key;
  
### Encrypt Type
encryption type supported currently:

  * aes(recommend)
  * rc4
  * plain(no encrypt)

### Other
recommend JVM arguments: ```-XX:+UseG1GC -XX:NewRatio=2 -XX:SurvivorRatio=6```
