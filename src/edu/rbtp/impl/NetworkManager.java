package edu.rbtp.impl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Roi Atalla
 */
public class NetworkManager {
	private DatagramChannel channel;
	private ConcurrentHashMap<Integer, RBTPConnection> connectionMap;
	
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
				if(instance == null)
					instance = new NetworkManager(port);
			}
		}
		
		return instance;
	}
	
	public synchronized RBTPConnection bindConnectionToAnyPort() throws IOException {
		int port;
		do {
			port = (int)Math.round(Math.random() * 256 * 256);
		} while(connectionMap.containsKey(port));
		
		return bindConnection(port);
	}
	
	public synchronized RBTPConnection bindConnection(int port) throws IOException {
		RBTPConnection connection = new RBTPConnection(port);
		if(connectionMap.putIfAbsent(port, connection) != null) {
			throw new IOException("port already bound.");
		}
		return connection;
	}
	
	private int checksumFailCount = 0;
	private int noMappingFoundCount = 0;
	
	private class NetworkManagerThread implements Runnable {
		@Override
		public void run() {
			ByteBuffer buffer = BufferPool.getInstance().getBuffer(4096); // 4K for now
			
			while(true) {
				try {
					int readCount = channel.read(buffer);
					if(readCount == -1) {
						throw new IOException("network channel is closed.");
					}
					
					buffer.flip();
					
					short recvdChecksum = buffer.getShort(12);
					if(calculateChecksum(buffer) != recvdChecksum) {
						checksumFailCount++;
						continue;
					}
					
					int destPort = buffer.getShort();
					RBTPConnection connection = connectionMap.get(destPort);
					if(connection == null) {
						noMappingFoundCount++;
						continue;
					}
					
					//TODO: feed the buffer to the connection
				}
				catch(Exception exc) {
					exc.printStackTrace();
					break;
				}
			}
		}
	}
	
	void sendPacket(RBTPPacket packet) {
		//TODO: implement sending a packet using channel.write
	}
	
	private int calculateChecksum(ByteBuffer buffer) {
		//TODO: Calculate CRC16 checksum
		
		return 0;
	}
}
