package edu.rbtp.impl;

import java.nio.ByteBuffer;

import edu.rbtp.RBTPSocketAddress;
import edu.rbtp.tools.BufferPool;

/**
 * @author Roi Atalla
 */
class RBTPPacket {
	public RBTPSocketAddress address;
	
	public int sourcePort;
	public int destinationPort;
	public long sequenceNumber;
	public int headerSize;
	public boolean syn, cha, ack, rej,fin, rst;
	public byte scale;
	public int checksum;
	public int receiveWindow;
	public ByteBuffer metadata;
	public ByteBuffer payload;
	
	public void decode(ByteBuffer buffer) {
		sourcePort = buffer.getShort() & 0xFFFF;
		destinationPort = buffer.getShort()  & 0xFFFF;
		sequenceNumber = buffer.getInt() & 0xFFFFFFFFL;
		headerSize = buffer.getShort() & 0xFFFF;
		
		short s = buffer.getShort(); 
		syn = (s & 0x8000) != 0;
		cha = (s & 0x4000) != 0;
		ack = (s & 0x2000) != 0;
		rej = (s & 0x1000) != 0;
		fin = (s & 0x0800) != 0;
		rst = (s & 0x0400) != 0;
		scale = (byte)(s & 0xF);
		
		checksum = buffer.getShort() & 0xFFFF;
		receiveWindow = buffer.getShort() & 0xFFFF;
		
		metadata = BufferPool.getBuffer((headerSize - 4) * 4);
		for(int i = 0; i < metadata.capacity(); i += 4) {
			metadata.putInt(i, buffer.getInt());
		}
		metadata.flip();
		
		payload = BufferPool.getBuffer(buffer.remaining());
		payload.put(buffer);
		payload.flip();
	}
	
	public void encode(ByteBuffer buffer) {
		int startPos = buffer.position();
		
		buffer.putShort((short)sourcePort);
		buffer.putShort((short)destinationPort);
		buffer.putInt((int)sequenceNumber);
		buffer.putShort((short)headerSize);
		
		short s = 0;
		s |= syn ? 0x8000 : 0;
		s |= cha ? 0x4000 : 0;
		s |= ack ? 0x2000 : 0;
		s |= rej ? 0x1000 : 0;
		s |= fin ? 0x0800 : 0;
		s |= rst ? 0x0400 : 0;
		s |= scale & 0xF;
		buffer.putShort(s);
		buffer.putShort((short)0);
		buffer.putShort((short)receiveWindow);
		metadata.clear(); // does not actually clear data, only resets position and limit
		buffer.put(metadata);
		payload.clear();
		buffer.put(payload);
		
		int prevLimit = buffer.limit();
		buffer.limit(buffer.position());
		buffer.position(startPos);
		
		checksum = calculateChecksum(buffer);
		buffer.putShort(12, (short)checksum);
		
		buffer.limit(prevLimit);
	}
	
	@Override
	protected void finalize() throws Throwable{
		destroy();
		
		super.finalize();
	}
	
	public void destroy() {
		if(metadata != null) {
			BufferPool.release(metadata);
			metadata = null;
		}
		if(payload != null) {
			BufferPool.release(payload);
			payload = null;
		}
	}
	
	public static int calculateChecksum(ByteBuffer buffer) {
		int checksum = 0xFFFF;
		
		// questions/13209364
		for(int i = 0; buffer.remaining() > 0; i++) {
			checksum = ((checksum >>> 8) | (checksum << 8)) & 0xFFFF;
			checksum ^= i == 12 || i == 13 ? 0 : buffer.get() & 0xFF; // Truncate sign;
			checksum ^= (checksum & 0xFF) >> 4;
			checksum ^= (checksum << 12) & 0xFFFF;
			checksum ^= ((checksum & 0xFF) << 5) & 0xFFFF;
		}
		
		checksum &= 0xFFFF; // Sign bit is carried over
		
		return checksum;
	}
}
