package edu.rbtp;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import edu.rbtp.impl.NetworkManager;
import edu.rbtp.impl.RBTPServer;

/**
 * @author Roi Atalla
 */
public class RBTPServerSocket {
	private int port;
	private RBTPServer serverHandler;
	private LinkedBlockingQueue<RBTPSocket> connectionsToAccept;
	private boolean blocking = true;
	
	public RBTPServerSocket() {
	}
	
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
	
	public void bind(int port) throws IOException {
		this.port = port;
		serverHandler = new RBTPServer();
		NetworkManager.getInstance().bindSocket((short)port, serverHandler);
	}
	
	public void listen() {
		if(serverHandler == null) {
			throw new IllegalStateException("SocketServer not bound.");
		}
		
		connectionsToAccept = new LinkedBlockingQueue<>();
		serverHandler.setAcceptHandler((connection) -> connectionsToAccept.offer(new RBTPSocket(blocking, connection)));
	}
	
	public RBTPSocket accept() {
		if(connectionsToAccept == null) {
			throw new IllegalStateException("SocketServer not listening.");
		}
		
		try {
			return blocking ? connectionsToAccept.take() : connectionsToAccept.poll();
		}
		catch(Exception exc) {
			exc.printStackTrace();
			return null;
		}
	}
	
	public void close() {
		serverHandler.close();
	}
}
