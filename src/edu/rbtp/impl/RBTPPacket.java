package edu.rbtp.impl;

import java.nio.ByteBuffer;

import edu.rbtp.RBTPSocketAddress;
import edu.rbtp.tools.BufferPool;

/**
 * @author Roi Atalla
 */
class RBTPPacket {
	public RBTPSocketAddress address;
	
	private short sourcePort;
	private short destinationPort;
	private int sequenceNumber;
	private short flags;
	private short receiveWindow;
	private ByteBuffer metadata;
	private ByteBuffer payload;
	
	@Override
	public boolean equals(Object o) {
		if(!(o instanceof RBTPPacket)) {
			return false;
		}
		RBTPPacket p = (RBTPPacket)o;
		return this.sequenceNumber() == p.sequenceNumber();
	}
	
	public int sourcePort() {
		return (int)sourcePort & 0xFFFF;
	}
	
	public void sourcePort(short sourcePort) {
		this.sourcePort = sourcePort;
	}
	
	public int destinationPort() {
		return (int)destinationPort & 0xFFFF;
	}
	
	public void destinationPort(short destinationPort) {
		this.destinationPort = destinationPort;
	}
	
	public long sequenceNumber() {
		return (long)sequenceNumber & 0xFFFFFFFFL;
	}
	
	public void sequenceNumber(int sequenceNumber) {
		this.sequenceNumber = sequenceNumber;
	}
	
	public int headerSize() {
		if(metadata != null && metadata.capacity() % 4 != 0) {
			throw new IllegalStateException("metadata capacity must be divisible by four");
		}
		
		return 4 + (metadata == null ? 0 : metadata.capacity() / 4);
	}
	
	public boolean syn() {
		return (flags & 0x8000) != 0;
	}
	
	public void syn(boolean syn) {
		flags |= syn ? 0x8000 : 0;
	}
	
	public boolean cha() {
		return (flags & 0x4000) != 0;
	}
	
	public void cha(boolean cha) {
		flags |= cha ? 0x4000 : 0;
	}
	
	public boolean ack() {
		return (flags & 0x2000) != 0;
	}
	
	public void ack(boolean ack) {
		flags |= ack ? 0x2000 : 0;
	}
	
	public boolean rej() {
		return (flags & 0x1000) != 0;
	}
	
	public void rej(boolean rej) {
		flags |= rej ? 0x1000 : 0;
	}
	
	public boolean fin() {
		return (flags & 0x0800) != 0;
	}
	
	public void fin(boolean fin) {
		flags |= fin ? 0x0800 : 0;
	}
	
	public boolean rst() {
		return (flags & 0x0400) != 0;
	}
	
	public void rst(boolean rst) {
		flags |= rst ? 0x0400 : 0;
	}
	
	public byte scale() {
		return (byte)(flags & 0xF);
	}
	
	public void scale(byte scale) {
		flags |= scale & 0xF;
	}
	
	public short flags() {
		return flags;
	}
	
	public int receiveWindow() {
		return (int)receiveWindow & 0xFFFF;
	}
	
	public void receiveWindow(short receiveWindow) {
		this.receiveWindow = receiveWindow;
	}
	
	public ByteBuffer metadata() {
		return metadata;
	}
	
	public void metadata(ByteBuffer metadata) {
		this.metadata = metadata;
	}
	
	public ByteBuffer payload() {
		return payload;
	}
	
	public void payload(ByteBuffer payload) {
		this.payload = payload;
	}
	
	public void decode(ByteBuffer buffer) {
		sourcePort = buffer.getShort();
		destinationPort = buffer.getShort();
		sequenceNumber = buffer.getInt();
		
		int headerSize = buffer.getShort();
		
		flags = buffer.getShort();
		
		short checksum = buffer.getShort();
		
		receiveWindow = buffer.getShort();
		
		if(headerSize > 4) {
			metadata = BufferPool.getBuffer((headerSize - 4) * 4);
			for(int i = 0; i < metadata.capacity(); i += 4) {
				metadata.putInt(buffer.getInt());
			}
			metadata.flip();
		}
		
		if(buffer.remaining() > 0) {
			payload = BufferPool.getBuffer(buffer.remaining());
			payload.put(buffer);
			payload.flip();
		}
		
		if(checksum() != checksum) {
			destroy();
			throw new IllegalStateException("Checksum does not match.");
		}
	}
	
	public void encode(ByteBuffer buffer) {
		buffer.putShort((short)sourcePort());
		buffer.putShort((short)destinationPort());
		buffer.putInt((int)sequenceNumber());
		buffer.putShort((short)headerSize());
		buffer.putShort(flags);
		buffer.putShort(checksum());
		buffer.putShort((short)receiveWindow());
		if(metadata != null) {
			metadata.clear(); // does not actually clear data, only resets position and limit
			buffer.put(metadata);
		}
		if(payload != null) {
			payload.clear();
			buffer.put(payload);
		}
	}
	
	@Override
	protected void finalize() throws Throwable {
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
	
	// CRC16 checksum
	public short checksum() {
		int checksum = 0xFFFF;
		
		checksum = calculateChecksum(checksum, (short)sourcePort());
		checksum = calculateChecksum(checksum, (short)destinationPort());
		checksum = calculateChecksum(checksum, (short)(sequenceNumber() >>> 16));
		checksum = calculateChecksum(checksum, (short)sequenceNumber());
		checksum = calculateChecksum(checksum, (short)headerSize());
		checksum = calculateChecksum(checksum, flags);
		checksum = calculateChecksum(checksum, (short)0);
		checksum = calculateChecksum(checksum, (short)receiveWindow());
		
		if(metadata != null) {
			metadata.clear();
			for(int i = 0; i < metadata.capacity(); i++) {
				checksum = calculateChecksum(checksum, metadata.get(i));
			}
		}
		
		if(payload != null) {
			payload.clear();
			for(int i = 0; i < payload.capacity(); i++) {
				checksum = calculateChecksum(checksum, payload.get(i));
			}
		}
		
		return (short)checksum;
	}
	
	private int calculateChecksum(int checksum, short value) {
		checksum = calculateChecksum(checksum, (byte)(value >>> 8));
		return calculateChecksum(checksum, (byte)(value & 0xFF));
	}
	
	private int calculateChecksum(int checksum, byte value) {
		checksum = ((checksum >>> 8) | (checksum << 8)) & 0xFFFF;
		checksum ^= (int)value & 0xFF; // Truncate sign;
		checksum ^= (checksum & 0xFF) >> 4;
		checksum ^= (checksum << 12) & 0xFFFF;
		checksum ^= ((checksum & 0xFF) << 5) & 0xFFFF;
		return checksum;
	}
}
