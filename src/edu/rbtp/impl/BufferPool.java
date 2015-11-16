package edu.rbtp.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * @author Roi Atalla
 */
class BufferPool {
	private ArrayList<ByteBuffer> pool;
	
	private BufferPool() {
		pool = new ArrayList<>();
	}
	
	private static BufferPool instance;
	
	public static BufferPool getInstance() {
		if(instance == null) {
			synchronized(BufferPool.class) {
				if(instance == null) {
					instance = new BufferPool();
				}
			}
		}
		
		return instance;
	}
	
	public synchronized ByteBuffer getBuffer(int size) {
		ByteBuffer best = null;
		for(ByteBuffer b : pool) {
			if(b.capacity() >= size && (best == null || b.capacity() < best.capacity()))
				best = b;
		}
		
		if(best == null) {
			best = ByteBuffer.allocate(size);
		}
		
		best.clear();
		best.order(ByteOrder.BIG_ENDIAN);
		
		return best;
	}
	
	public synchronized void release(ByteBuffer buffer) {
		pool.add(buffer);
	}
}
