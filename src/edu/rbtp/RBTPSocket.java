package edu.rbtp;

import java.nio.ByteBuffer;

/**
 * @author Roi Atalla
 */
public abstract class RBTPSocket {
	public abstract void setBlocking(boolean blocking);
	
	public abstract boolean isBlocking();
	
	public abstract void connect(RBTPSocketAddress address);
	
	public abstract boolean isConnected();
	
	public long read(ByteBuffer buffer) {
		return read(buffer, buffer.position(), buffer.remaining());
	}
	
	public abstract long read(ByteBuffer buffer, int offset, int length);
	
	public long send(ByteBuffer buffer) {
		return send(buffer, buffer.position(), buffer.remaining());
	}
	
	public abstract long send(ByteBuffer buffer, int offset, int length);
}
