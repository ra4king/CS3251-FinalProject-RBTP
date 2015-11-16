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
		
		new Thread(new NetworkManagerThread()).start();
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
	
	int bindConnectionToAnyPort(RBTPConnection connection) throws IOException {
		int port = (int)Math.round(Math.random() * 256 * 256);
		bindConnection(port, connection);
		return port;
	}
	
	void bindConnection(int port, RBTPConnection connection) throws IOException {
		if(connectionMap.putIfAbsent(port, connection) != null) {
			throw new IOException("port already bound.");
		}
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
