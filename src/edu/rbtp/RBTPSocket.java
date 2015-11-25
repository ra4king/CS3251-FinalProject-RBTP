package edu.rbtp;

import java.io.IOException;
import java.nio.ByteBuffer;

import edu.rbtp.impl.NetworkManager;
import edu.rbtp.impl.RBTPConnection;

/**
 * @author Roi Atalla
 */
public class RBTPSocket {
	private boolean blocking;
	private RBTPConnection connection;
	
	public RBTPSocket() {
		this(true);
	}
	
	public RBTPSocket(boolean blocking) {
		this.blocking = blocking;
	}
	
	RBTPSocket(boolean blocking, RBTPConnection connection) {
		this.blocking = blocking;
		this.connection = connection;
	}
	
	public void setBlocking(boolean blocking) {
		this.blocking = blocking;
	}
	
	public boolean isBlocking() {
		return blocking;
	}
	
	public void connect(RBTPSocketAddress address) throws IOException {
		if(connection != null)
			throw new IllegalStateException("Already connected.");
		
		connection = new RBTPConnection();
		NetworkManager.getInstance().bindSocketToAnyPort(connection);
		
		connection.connect(address);
	}
	
	public boolean isConnected() {
		return connection != null && connection.isClosed();
	}
	
	public int read(ByteBuffer buffer) {
		return connection.read(buffer, blocking);
	}
	
	public int write(ByteBuffer buffer) {
		return connection.write(buffer, blocking);
	}
	
	public boolean isClosed() {
		return connection.isClosed();
	}
	
	public void close() {
		connection.close();
	}
}
