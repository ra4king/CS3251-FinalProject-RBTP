package edu.rbtp.impl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import edu.rbtp.RBTPSocketAddress;

/**
 * @author Roi Atalla, Evan Bailey
 */
public class NetworkManager {
	private DatagramChannel channel;
	private ConcurrentHashMap<Integer, ConnectionInfo> connectionMap;
	
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
		return instance;
	}
	
	public static NetworkManager init(String address, int port) throws IOException {
		if(instance == null) {
			synchronized(NetworkManager.class) {
				if(instance == null) {
					instance = new NetworkManager(port);
				}
			}
		}
		
		return instance;
	}
	
	public synchronized RBTPConnection createConnectionOnAnyPort() throws IOException {
		int port;
		do {
			port = (int)Math.round(Math.random() * 256 * 256);
		} while(connectionMap.containsKey(port));
		
		return createConnection(port);
	}
	
	public synchronized RBTPConnection createConnection(int port) throws IOException {
		RBTPConnection connection = new RBTPConnection(port);
		ConnectionInfo connectionInfo = this.new ConnectionInfo(connection);
		connectionInfo.packetsReceived = connection.initialize(connectionInfo);
		
		
		if(connectionMap.putIfAbsent(port, connectionInfo) != null) {
			throw new IOException("port already bound.");
		}
		return connection;
	}
	
	private class ConnectionInfo implements Consumer<RBTPPacket> {
		RBTPConnection connection;
		Consumer<RBTPPacket> packetsReceived;
		
		ConnectionInfo(RBTPConnection connection) {
			this.connection = connection;
		}
		
		private ByteBuffer sendBuffer = ByteBuffer.allocateDirect(4096);
		
		@Override
		public void accept(RBTPPacket packet) {
			sendBuffer.clear();
			packet.encode(sendBuffer);
			
			try {
				channel.send(sendBuffer, packet.address.getAddress());
			} catch(IOException exc) {
				throw new RuntimeException(exc);
			}
		}
	}
	
	public int checksumFailCount = 0;
	public int noMappingFoundCount = 0;
	
	private class NetworkManagerThread implements Runnable {
		@Override
		public void run() {
			ByteBuffer buffer = ByteBuffer.allocateDirect(4096); // 4K for now
			
			while(true) {
				try {
					SocketAddress address = channel.receive(buffer);
					buffer.flip();
					
					short recvdChecksum = buffer.getShort(12);
					if(RBTPPacket.calculateChecksum(buffer) != recvdChecksum) {
						checksumFailCount++;
						continue;
					}
					
					buffer.flip();
					RBTPPacket packet = new RBTPPacket();
					packet.decode(buffer);
					
					ConnectionInfo connection = connectionMap.get(packet.destinationPort);
					if(connection == null) {
						noMappingFoundCount++;
						continue;
					}
					
					packet.address = new RBTPSocketAddress(address, packet.sourcePort);
					
					connection.packetsReceived.accept(packet);
				}
				catch(Exception exc) {
					exc.printStackTrace();
					break;
				}
			}
		}
	}
}
