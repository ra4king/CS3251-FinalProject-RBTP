package edu.rbtp;

/**
 * @author Roi Atalla
 */
public interface RBTPServerSocket {
	void setBlock(boolean blocking);
	
	boolean isBlocking();
	
	void bind(RBTPSocketAddress address);
	
	void listen();
	
	RBTPSocket accept();
	
	RBTPSocket accept(long timeout);
}
