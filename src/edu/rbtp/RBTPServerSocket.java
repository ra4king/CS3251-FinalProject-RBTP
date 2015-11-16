package edu.rbtp;

/**
 * @author Roi Atalla
 */
public abstract class RBTPServerSocket {
	abstract void setBlock(boolean blocking);
	
	abstract boolean isBlocking();
	
	abstract void bind(RBTPSocketAddress address);
	
	abstract void listen();
	
	public RBTPSocket accept() {
		return accept(0);
	}
	
	abstract RBTPSocket accept(long timeout);
}
