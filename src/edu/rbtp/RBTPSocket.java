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
	
	public long read(ByteBuffer buffer) {
		return read(buffer, buffer.position(), buffer.remaining());
	}
	
	public long read(ByteBuffer buffer, int offset, int length) {
		return connection.read(buffer, offset, length, blocking);
	}
	
	public long send(ByteBuffer buffer) {
		return send(buffer, buffer.position(), buffer.remaining());
	}
	
	public long send(ByteBuffer buffer, int offset, int length) {
		return connection.send(buffer, offset, length, blocking);
	}
	
	public boolean isClosed() {
		return connection.isClosed();
	}
	
	public void close() {
		connection.close();
	}
}
