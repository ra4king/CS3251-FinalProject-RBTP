package edu.rbtp.impl;

import java.nio.ByteBuffer;

/**
 * @author Roi Atalla
 */
class RBTPPacket {
	public int sourcePort;
	public int destinationPort;
	public long sequenceNumber;
	public int headerSize;
	public boolean syn, cha, ack, rej,fin, rst;
	public byte scale;
	public ByteBuffer metadata;
	public ByteBuffer payload;
}
