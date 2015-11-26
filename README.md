|===================================|
|                                   |
|  CS 3251 - Programming Project 2  |
|                                   |
|===================================|

25 November 2015

Programming Project 2 README
 + Prepared for CS 3251-B

Authors:
 + Roi Atalla  - ratalla3@gatech.edu
 + Evan Bailey - evanbailey122@gatech.edu


---------
  ABOUT
---------

This section will describe the 2 protocols which have been implemented in this project.

 + Reliable Bytestream Transport Protocol (RBTP)
   | This protocol's description can be found in the PDF "CS 3251 Homework 4.pdf"
   | No major changes were made to the protocol since that PDF was made.
   
 + Simple File Transfer Protocol (SimpleFTP)
   | This protocol describes a very basic file-transfer application
   | A SimpleFTP message consists of raw bytes in the following format:
   | <clength><opcode><content>
   |   + clength: 4-byte integer indicating the length of the rest of the message
   |
   |   + opcode : 1-byte field denoting the type of message
   |              0x00 - ERR - Error message. Can denote unsuccessful response.
   |              0x01 - GET - message is a GET request.
   |              0x02 - PUT - message is a PUT request.
   |              0x03 - RSP - message is a response to a prior request.
   |
   |   + content: varying-length field containing data.
   |
   | GET
   |   + This is very straightforward, client simply sends a GET message to the server
   |
   | PUT
   |   + This is only slightly more complex than GET. First, the client sends PUT to the server
   |     with the filename.
   |     The server either responds with RSP or ERR; if ERR, abort PUT.
   |     If the server responded with RSP, the client will finally send the file's bytes to the server.


---------
  FILES
---------

For more detailed descriptions of each file, see the documentation present in the file itself.

SimpleFTP.java
- This file provides basic protocol definitions for SimpleFTP

SimpleFTPClientLauncher.java
- This file launches and runs a SimpleFTP Client

SimpleFTPServerLauncher.java
- This file launches and runs a SimpleFTP Server

SimpleFTPClient.java
- SimpleFTP clinet implementation

SimpleFTPServer.java
- SimpleFTP server implementation

RBTPServerSocket.java
- Defines an RBTP server socket

RBTPSocket.java
- Defines an RBTP socket

RBTPSocketAddress.java
- Data structure used by RBTP sockets (contains InetAddress, NetworkManager, RBTP port)

BufferPool.java
- Allows access to memory-managed buffers

Bindable.java
- Defines the Bindable interface, denoting an object which can be bound to a connection

BindingInterface.java
- Defines a relationship between a Bindable and the connection it is bound to

NetworkManager.java
- Handles multiplexing. Reads from UDP socket and passes packets to the connection associated with it

RBTPConnection.java
- Implementation of RBTP

RBTPPacket.java
- Definition of an RBTP packets

RBTPServer.java
- Middleman through which NetworkManager and RBTPconnections communicate; RBTPConnections bind to this
  rather than the NetworkManager, allowing each RBTPserver to handle its own multiplexing.


---------
  USAGE
---------

TODO


--------
  MISC
--------

Current limitations:
- SimpleFTP can only transfer files up to ~ 2GB in size.
  Limitation comes from the length field of the SimpleFTP message being an integer, and as such the
  maximum attainable value of an integer is the largest file size that can be dealt with.

- Since a SimpleFTP PUT request is broken into 2 parts, the consecutive parts must occur
  immediately after one another.
