package edu.rbtp.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * @author Roi Atalla
 */
class BufferPool {
	private BufferPool() {}
	
	private static ArrayList<ByteBuffer> pool = new ArrayList<>();
	
	public static synchronized ByteBuffer getBuffer(int size) {
		ByteBuffer best = null;
		for(ByteBuffer b : pool) {
			if(b.capacity() >= size && b.capacity() < size * 3 &&  (best == null || b.capacity() < best.capacity()))
				best = b;
		}
		
		if(best == null) {
			best = ByteBuffer.allocate(size);
		}
		
		best.clear();
		best.order(ByteOrder.BIG_ENDIAN);
		
		return best;
	}
	
	public static synchronized void release(ByteBuffer buffer) {
		pool.add(buffer);
	}
}
