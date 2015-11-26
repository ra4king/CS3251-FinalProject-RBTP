package edu.rbtp;

import java.io.IOException;
import java.nio.ByteBuffer;

import edu.rbtp.impl.NetworkManager;
import edu.rbtp.impl.RBTPConnection;

/**
 * This is the interface by which to communicate with a remote through the RBT protocol.
 * 
 * @author Roi Atalla
 */
public class RBTPSocket {
	private boolean blocking;
	private RBTPConnection connection;
	
	/**
	 * Initializes the socket with blocking mode set to true.
	 */
	public RBTPSocket() {
		this(true);
	}
	
	/**
	 * Initializes the socket with the specified blocking mode.
	 * @param blocking the blocking mode
	 */
	public RBTPSocket(boolean blocking) {
		this.blocking = blocking;
	}
	
	/**
	 * For internal use by the RBTPServerSocket.
	 */
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
	
	/**
	 * Connects to the socket to the remote RBTP server. Does not return until a connection is successfully made.
	 *
	 * @param address the remote address
	 * @throws IOException
	 */
	public void connect(RBTPSocketAddress address) throws IOException {
		if(connection != null) {
			throw new IllegalStateException("Already connected.");
		}
		
		connection = new RBTPConnection();
		NetworkManager.getInstance().bindSocketToAnyPort(connection);
		
		connection.connect(address);
	}
	
	public boolean isConnected() {
		return connection != null && connection.isClosed();
	}
	
	/**
	 * Read into the buffer as much data as possible, returning the number of bytes read.
	 *
	 * @param buffer The ByteBuffer into which to read data
	 * @return the number of bytes read
	 * @throws IOException
	 */
	public int read(ByteBuffer buffer) throws IOException {
		return connection.read(buffer, blocking);
	}
	
	/**
	 * Writes from the buffer as much data as possible, returning the number of bytes written.
	 *
	 * @param buffer The ByteBuffer from which to write data
	 * @return the number of bytes written
	 * @throws IOException
	 */
	public int write(ByteBuffer buffer) throws IOException {
		return connection.write(buffer);
	}
	
	public boolean isClosed() {
		return connection.isClosed();
	}
	
	public void close() {
		connection.close();
	}
	
	/**
	 * Get a reference to the internal RBTPConnection class. DO NOT TOUCH IF YOU DON'T KNOW WHAT YOU'RE DOING!
	 * @return the backing RBTPConnection
	 */
	public RBTPConnection getConnection() {
		return connection;
	}
}
