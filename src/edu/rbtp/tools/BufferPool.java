package edu.rbtp.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;

/**
 * @author Roi Atalla
 */
public class BufferPool {
	private BufferPool() {}
	
	private static HashSet<ByteBuffer> pool = new HashSet<>();
	
	private static int buffersCreated = 0;
	
	public static synchronized int getBuffersCreatedCount() {
		return buffersCreated;
	}
	
	public static synchronized ByteBuffer getBuffer(int size) {
		ByteBuffer best = null;
		for(ByteBuffer b : pool) {
			if(b.capacity() >= size && b.capacity() < size * 2 &&  (best == null || b.capacity() < best.capacity()))
				best = b;
		}
		
		if(best == null) {
			best = ByteBuffer.allocate(size);
			buffersCreated++;
			System.out.println("CREATING NEW BUFFER. COUNT: " + buffersCreated);
			
			try {
				throw new Exception();
			} catch(Exception exc) {
				exc.printStackTrace();
			}
		}
		else {
			pool.remove(best);
		}
		
		best.clear();
		best.order(ByteOrder.BIG_ENDIAN);
		
		return best;
	}
	
	public static synchronized void release(ByteBuffer buffer) {
		if(buffer != null && !pool.contains(buffer))
			pool.add(buffer);
	}
}
