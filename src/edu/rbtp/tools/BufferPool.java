package edu.rbtp.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;

/**
 * @author Roi Atalla
 */
public class BufferPool {
	public static boolean PRINT_DEBUG = false;
	
	private BufferPool() {}
	
	private static HashSet<ByteBufferWrapper> pool = new HashSet<>();
	
	private static int buffersCreated = 0;
	
	public static synchronized int getBuffersCreatedCount() {
		return buffersCreated;
	}
	
	public static synchronized ByteBuffer getBuffer(int size) {
		ByteBufferWrapper best = null;
		for(ByteBufferWrapper b : pool) {
			if(b.buffer.capacity() == size) {
				best = b;
			}
		}
		
		if(best == null) {
			best = new ByteBufferWrapper(ByteBuffer.allocateDirect(size));
			buffersCreated++;
		}
		else {
			pool.remove(best);
		}
		
		best.buffer.clear();
		best.buffer.order(ByteOrder.BIG_ENDIAN);
		
		return best.buffer;
	}
	
	public static synchronized void release(ByteBuffer buffer) {
		ByteBufferWrapper wrapper = new ByteBufferWrapper(buffer);
		
		if(buffer != null && !pool.contains(wrapper))
			pool.add(wrapper);
	}
	
	// This is so buffers are checked for equal by instance, not by contents
	private static class ByteBufferWrapper {
		private ByteBuffer buffer;
		
		ByteBufferWrapper(ByteBuffer buffer) {
			this.buffer = buffer;
		}
		
		@Override
		public int hashCode() {
			return buffer.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if(!(o instanceof ByteBufferWrapper))
				return false;
			ByteBufferWrapper wrapper = (ByteBufferWrapper)o;
			return this.buffer == wrapper.buffer;
		}
	}
}
