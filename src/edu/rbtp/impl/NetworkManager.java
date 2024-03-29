package edu.rbtp.impl;

import static edu.rbtp.tools.BufferPool.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import edu.rbtp.RBTPSocketAddress;
import edu.rbtp.tools.BufferPool;

/**
 * This class handles reading from the UDP socket and multiplexing to the bound Bindables.
 *
 * @author Roi Atalla
 */
public class NetworkManager {
	private DatagramChannel channel;
	private ConcurrentHashMap<Short, ConnectionInfo> connectionMap;
	
	private NetworkManager(int port) throws IOException {
		channel = DatagramChannel.open();
		channel.bind(new InetSocketAddress(port));
		
		connectionMap = new ConcurrentHashMap<>();
		
		Thread t = new Thread(new NetworkManagerThread());
		t.setName("RBTP Network Manager Thread");
		t.setDaemon(true);
		t.start();
	}
	
	private static NetworkManager instance = null;
	
	public static NetworkManager getInstance() {
		if(instance == null) {
			throw new IllegalStateException("NetworkManager not initialized: NetworkManger.init(int UDPport)");
		}
		
		return instance;
	}
	
	/**
	 * This init method *MUST* be called before using NetworkManager at all.
	 *
	 * @param UDPport the UDP port to bind to
	 * @return the NetworkManager singleton instance
	 * @throws IOException
	 */
	public static NetworkManager init(int UDPport) throws IOException {
		if(instance == null) {
			synchronized(NetworkManager.class) {
				if(instance == null) {
					instance = new NetworkManager(UDPport);
				}
			}
		} else {
			throw new IllegalStateException("NetworkManager already initialized.");
		}
		
		return instance;
	}
	
	public synchronized void bindSocketToAnyPort(Bindable socket) throws IOException {
		short port;
		do {
			port = (short)(Math.random() * 256 * 256);
		} while(connectionMap.containsKey(port));
		
		bindSocket(port, socket);
	}
	
	public synchronized void bindSocket(short port, Bindable socket) throws IOException {
		if(socket.isBound()) {
			throw new IllegalArgumentException("Socket is already bound.");
		}
		
		ConnectionInfo connectionInfo = this.new ConnectionInfo(port, socket);
		
		if(connectionMap.putIfAbsent(port, connectionInfo) != null) {
			throw new IOException("port already bound.");
		}
		
		socket.bind(connectionInfo);
		if(connectionInfo.getPacketReceivedConsumer() == null) {
			throw new IllegalStateException("Did not set packetReceivedConsumer.");
		}
	}
	
	private class ConnectionInfo implements BindingInterface, Consumer<RBTPPacket> {
		short port;
		Bindable socket;
		Consumer<RBTPPacket> packetReceived;
		
		ConnectionInfo(short port, Bindable socket) {
			this.port = port;
			this.socket = socket;
		}
		
		@Override
		public short getPort() {
			return port;
		}
		
		@Override
		public Consumer<RBTPPacket> getPacketSendConsumer() {
			return this;
		}
		
		@Override
		public Consumer<RBTPPacket> getPacketReceivedConsumer() {
			return packetReceived;
		}
		
		@Override
		public void setPacketReceivedConsumer(Consumer<RBTPPacket> packetReceived) {
			this.packetReceived = packetReceived;
		}
		
		@Override
		public void unbind() {
			connectionMap.remove(port);
		}
		
		private ByteBuffer sendBuffer = ByteBuffer.allocateDirect(8192);
		
		@Override
		public synchronized void accept(RBTPPacket packet) {
			sendBuffer.clear();
			packet.encode(sendBuffer);
			sendBuffer.flip();
			
			if(PRINT_DEBUG) {
				System.out.println("NetworkManager: sending packet seq: " + packet.sequenceNumber() + ", dest: " + packet.address);
			}
			
			try {
				while(channel.send(sendBuffer, packet.address.getAddress()) == 0)
					if(PRINT_DEBUG) {
						System.out.println("NetworkManager: FAILED TO WRITE BYTES!");
					}
			}
			catch(IOException exc) {
				exc.printStackTrace();
				throw new RuntimeException(exc);
			}
		}
	}
	
	public int checksumFailCount = 0;
	public int noMappingFoundCount = 0;
	
	/**
	 * The actual packet handling. This thread reads UDP packets, checks for bad checksums, and multiplexes the packets to the appropriate Bindable.
	 */
	private class NetworkManagerThread implements Runnable {
		@Override
		public void run() {
			ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
			
			while(true) {
				try {
					buffer.clear();
					SocketAddress address = channel.receive(buffer);
					buffer.flip();
					
					if(PRINT_DEBUG) {
						System.out.println("NetworkManager: Received packet!");
					}
					
					RBTPPacket packet = new RBTPPacket();
					try {
						packet.decode(buffer);
					}
					catch(Exception exc) {
						checksumFailCount++;
						if(PRINT_DEBUG) {
							System.out.println("NetworkManager: FAILED CHECKSUM!");
						}
						continue;
					}
					
					if(PRINT_DEBUG) {
						System.out.println(BufferPool.getBuffersCreatedCount() + " buffers created so far.");
					}
					
					ConnectionInfo connection = connectionMap.get((short)packet.destinationPort());
					if(connection == null) {
						noMappingFoundCount++;
						if(PRINT_DEBUG) {
							System.out.println("NetworkManager: no mapping found for port " + packet.destinationPort());
						}
						packet.destroy();
						continue;
					}
					
					packet.address = new RBTPSocketAddress((InetSocketAddress)address, packet.sourcePort());
					if(PRINT_DEBUG) {
						System.out.println("NetworkManager: received packet seq: " + packet.sequenceNumber() + ", destPort: " + packet.destinationPort() + ", source: " + packet.address);
					}
					
					connection.packetReceived.accept(packet);
				}
				catch(Exception exc) {
					exc.printStackTrace();
					break;
				}
			}
		}
	}
}
