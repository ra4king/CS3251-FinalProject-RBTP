package edu.rbtp;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import edu.rbtp.impl.NetworkManager;
import edu.rbtp.impl.RBTPServer;

/**
 * @author Roi Atalla
 */
public class RBTPServerSocket {
	private int port;
	private RBTPServer serverHandler;
	private LinkedBlockingQueue<RBTPSocket> connectionsToAccept;
	private boolean blocking;
	
	public RBTPServerSocket() {}
	
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
		NetworkManager.getInstance().bindSocket(port, serverHandler);
	}
	
	public void listen() {
		serverHandler.setAcceptHandler((connection) -> connectionsToAccept.offer(new RBTPSocket(blocking, connection)));
	}
	
	public RBTPSocket accept() {
		return accept(0);
	}
	
	public RBTPSocket accept(long timeout) {
		try {
			return blocking ? connectionsToAccept.poll() : connectionsToAccept.poll(0, TimeUnit.MILLISECONDS);
		} catch(Exception exc) {
			return null;
		}
	}
	
	public void close() {
		serverHandler.close();
	}
}
