### About
Hikari, A proxy tool which helps you break through network restrictions.

The Hikari client listens on a local socket port and wait for incoming SOCKS4 or SOCKS5 requests,
when incoming SOCKS requests arrived, the client process the request and try to connect to Hikari server using Hikari protocol.
When a connection has been established between Hikari client and Hikari server,
the client side response the SOCKS request and is ready for proxy.

Process:

> local app <---[plaintext]---> Hikari client <---[Hikari protocol (ciphertext)]---> Hikari server <---[plaintext]---> target app

### Usage
1. Install Java (version 8 or newer);
2. Download the server or client .jar file and configuration file;
3. Run command: ```java -jar /path/to/jar_file/xxx.jar /path/to/config_file/xxx.json```;
4. Enjoy;

### Configuration
* Server Configuration:
  * listenAddress: Listening address;
  * listenPortList: Related port;
  * privateKeyList: Allowed user keys list;
  * bufferSize: Size of data buffer, default value is 2048, if you are not sure, keep the default;
  * encryptType: Encryption type (see down below), must be same with server;
  * secret: The key that used to encryption, must be same with server;

* Client Configuration:
  * listenAddress: Listening address, most commonly use 'localhost' or '127.0.0.1';
  * listenPort: Related port;
  * localDnsResolve: 'true': Use local DNS resolution; 'false': Use remote(server) DNS resolution;
  * bufferSize: Size of data buffer, default value is 2048, if you are not sure, keep the default;
  * encryptType: Encryption type (see down below), must be same with server;
  * secret: The key that used to encryption, must be same with server;
  * serverAddress: Server's listening address, IP address or Hostname;
  * serverPortList: Server's listening port;
  * privateKey: User key;
  
### Supported Encryption Types
  * AES (recommend)
  * RC4

### Others
Recommended JVM arguments: ```-XX:+UseG1GC -XX:NewRatio=2 -XX:SurvivorRatio=6```
