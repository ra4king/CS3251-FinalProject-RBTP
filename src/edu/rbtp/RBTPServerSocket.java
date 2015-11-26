package edu.rbtp;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import edu.rbtp.impl.NetworkManager;
import edu.rbtp.impl.RBTPServer;

/**
 * This class starts a server that can accept connections through the RBT protocol.
 * 
 * @author Roi Atalla
 */
public class RBTPServerSocket {
	private int port;
	private RBTPServer serverHandler;
	private LinkedBlockingQueue<RBTPSocket> connectionsToAccept;
	private boolean blocking;
	
	/**
	 * Initializes this server with blocking mode set to true.
	 */
	public RBTPServerSocket() {
		this(true);
	}
	
	/**
	 * Initializes this server with the specified blocking mode.
	 *
	 * @param blocking the blocking mode
	 */
	public RBTPServerSocket(boolean blocking) {
		this.blocking = blocking;
	}
	
	/**
	 * Binds this server to the specified port.
	 *
	 * @param port the port to bind to
	 * @throws IOException
	 */
	public RBTPServerSocket(int port) throws IOException {
		bind(port);
	}
	
	public void setBlocking(boolean blocking) {
		this.blocking = blocking;
	}
	
	public boolean isBlocking() {
		return blocking;
	}
	
	public int getPort() {
		return port;
	}
	
	/**
	 * Binds this server to the specified port.
	 *
	 * @param port the port to bind to
	 * @throws IOException
	 */
	public void bind(int port) throws IOException {
		this.port = port;
		serverHandler = new RBTPServer();
		NetworkManager.getInstance().bindSocket((short)port, serverHandler);
	}
	
	/**
	 * Must be called before calling accept()
	 * The server starts listening for SYN packets.
	 */
	public void listen() {
		if(serverHandler == null) {
			throw new IllegalStateException("SocketServer not bound.");
		}
		
		connectionsToAccept = new LinkedBlockingQueue<>();
		serverHandler.setAcceptHandler((connection) -> connectionsToAccept.offer(new RBTPSocket(blocking, connection)));
	}
	
	/**
	 * In blocking mode, this method blocks until a client has connected.
	 *
	 * @return the RBTPSocket of a new connection
	 */
	public RBTPSocket accept() throws IOException {
		if(connectionsToAccept == null) {
			throw new IllegalStateException("SocketServer not listening.");
		}
		
		if(blocking) {
			RBTPSocket socket = null;
			do {
				try {
					socket = connectionsToAccept.poll(10, TimeUnit.MILLISECONDS);
				}
				catch(Exception exc) {
				}
			} while(socket == null && !isClosed());
			
			if(isClosed()) {
				throw new IOException("Server is closed.");
			}
			
			return socket;
		} else {
			return connectionsToAccept.poll();
		}
	}
	
	public boolean isClosed() {
		return serverHandler.isClosed();
	}
	
	public void close() {
		serverHandler.close();
	}
}
