package edu.rbtp.impl;

import static edu.rbtp.tools.BufferPool.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import edu.rbtp.RBTPSocketAddress;
import edu.rbtp.tools.BufferPool;

/**
 * The RBTPConnection is where all the action happens. Complete implementation of RBTP.
 *
 * @author Roi Atalla
 */
public class RBTPConnection implements Bindable {
	private enum RBTPConnectionState {
		CLOSED, SYN_RCVD, SYN_SENT, ACK_CHA_SENT, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2,
		CLOSING, TIMED_WAIT, CLOSE_WAIT, LAST_ACK
	}
	
	private volatile RBTPConnectionState state;
	private int maxWindowSize = 10000;
	private final long TIMEOUT = 200;
	private final int TIMEOUT_COUNT_LIMIT = 100;
	
	private int duplicateCount = 0;
	private int dataPackets = 0;
	private int totalPackets = 0;
	
	private volatile boolean requestClose;
	private BindingInterface bindingInterface;
	private Consumer<RBTPPacket> sendPacket;
	private RBTPOutputStreamThread outputStreamThread;
	private RBTPInputStreamThread inputStreamThread;
	private RBTPSocketAddress remoteAddress;
	
	public RBTPConnection() {
		state = RBTPConnectionState.CLOSED;
	}
	
	public boolean isBound() {
		return bindingInterface != null;
	}
	
	public void bind(BindingInterface bindingInterface) {
		if(this.bindingInterface != null) {
			throw new IllegalStateException("Already bound.");
		}
		
		this.bindingInterface = bindingInterface;
		this.sendPacket = bindingInterface.getPacketSendConsumer();
		
		inputStreamThread = new RBTPInputStreamThread();
		outputStreamThread = new RBTPOutputStreamThread();
		bindingInterface.setPacketReceivedConsumer(inputStreamThread);
	}
	
	public RBTPSocketAddress getRemoteAddress() {
		return remoteAddress;
	}
	
	public int getWindowSize() {
		return maxWindowSize;
	}
	
	public void setWindowSize(int windowSize) {
		this.maxWindowSize = windowSize;
	}
	
	private static Random rng = new Random();
	
	/**
	 * The connect sends the SYN packet and initiates the connection to the remote address.
	 *
	 * @param address the remote address to connect to
	 * @throws IOException
	 */
	public synchronized void connect(RBTPSocketAddress address) throws IOException {
		if(remoteAddress != null) {
			throw new IllegalStateException("Already connected.");
		}
		
		if(bindingInterface == null) {
			throw new IllegalStateException("Socket not bound.");
		}
		
		remoteAddress = address;
		
		state = RBTPConnectionState.SYN_SENT;
		RBTPPacket synPacket = new RBTPPacket();
		setupPacket(synPacket, maxWindowSize);
		synPacket.sequenceNumber(rng.nextInt()); // choose a random starting sequence number
		synPacket.syn(true);
		
		sendPacket.accept(synPacket);
		
		inputStreamThread.init(synPacket);
		
		startNetworkThreads();
		
		while(state != RBTPConnectionState.ESTABLISHED && state != RBTPConnectionState.CLOSED) {
			try {
				Thread.sleep(10);
			}
			catch(InterruptedException exc) {
			}
		}
		
		if(state != RBTPConnectionState.ESTABLISHED) {
			close();
			throw new IOException("Connect failed. state: " + state);
		}
	}
	
	/**
	 * This is called by the server implementation. The received SYN packet is passed to the accept method
	 * and the SYN-CHA packet is sent back.
	 *
	 * @param synPacket the SYN packet the server received.
	 */
	void accept(RBTPPacket synPacket) {
		state = RBTPConnectionState.SYN_RCVD;
		
		remoteAddress = synPacket.address;
		
		RBTPPacket chaPacket = new RBTPPacket();
		setupPacket(chaPacket, this.maxWindowSize);
		chaPacket.sequenceNumber(rng.nextInt()); // choose a random starting sequence number
		chaPacket.syn(true);
		chaPacket.cha(true);
		ByteBuffer metadata = BufferPool.getBuffer(8);
		long randValue = rng.nextLong() & 0xFFFFFFFFFFFFFFL; // the challenge is a random 56-bit value
		for(int i = 6; i >= 0; i--) {
			metadata.put((byte)(randValue >>> (i * 8)));
		}
		metadata.put((byte)13); // 13 zeroes for now, should be an average of 100ms
		metadata.flip();
		chaPacket.metadata(metadata);
		
		sendPacket.accept(chaPacket);
		
		inputStreamThread.init(chaPacket);
		
		startNetworkThreads();
		
		if(PRINT_DEBUG) {
			System.out.println("SERVER: Accepted SYN, sending SYN-CHA, seq: " + chaPacket.sequenceNumber() + ", RandNum: " + randValue);
		}
	}
	
	private void startNetworkThreads() {
		Thread ost = new Thread(outputStreamThread);
		ost.setName("RBTP Output Stream Thread port: " + bindingInterface.getPort());
		ost.start();
		
		Thread ist = new Thread(inputStreamThread);
		ist.setName("RBTP Input Stream Thread port: " + bindingInterface.getPort());
		ist.start();
		
		// Wait for all threads to terminate to unbind this Bindable
		new Thread(() -> {
			while(ost.isAlive()) {
				try {
					ost.join();
				}
				catch(Exception exc) {
				}
			}
			
			while(ist.isAlive()) {
				try {
					ist.join();
				}
				catch(Exception exc) {
				}
			}
			
			if(PRINT_DEBUG) {
				System.out.println("CONNECTION: Unbinding.");
			}
			bindingInterface.unbind();
		}).start();
	}
	
	private void setupPacket(RBTPPacket packet, int receiveWindow) {
		packet.address = remoteAddress;
		packet.sourcePort(bindingInterface.getPort());
		packet.destinationPort(remoteAddress.getPort());
		
		byte scale = 0;
		
		while(receiveWindow > 0xFFFF) {
			receiveWindow >>>= 1;
			scale++;
		}
		
		packet.scale(scale);
		packet.receiveWindow((short)receiveWindow);
	}
	
	/**
	 * This method reads as much as it can and returns the number of bytes read.
	 * During blocking mode, it will return when it reads at least 1 byte.
	 *
	 * @param data  reads data in the remaining space
	 * @param block to block or not to block
	 * @return the number of bytes read
	 * @throws IOException
	 */
	public int read(ByteBuffer data, boolean block) throws IOException {
		if(block) {
			int read;
			while((read = inputStreamThread.read(data)) == 0 && !isClosed()) {
				try {
					Thread.sleep(100);
				}
				catch(Exception exc) {
				}
			}
			
			return read;
		} else {
			return inputStreamThread.read(data);
		}
	}
	
	/**
	 * This method writes as much as it can and returns the number of bytes written.
	 *
	 * @param data writes data from the remaining space
	 * @return the number of bytes written
	 * @throws IOException
	 */
	public int write(ByteBuffer data) throws IOException {
		return outputStreamThread.write(data);
	}
	
	public boolean isConnected() {
		return state == RBTPConnectionState.ESTABLISHED;
	}
	
	public boolean isClosed() {
		return state == RBTPConnectionState.CLOSED;
	}
	
	public void close() {
		requestClose = true;
	}
	
	/**
	 * This class handles all data packets being sent to the remote.
	 */
	private class RBTPOutputStreamThread implements Runnable {
		private ArrayList<RBTPPacket> lastSent;
		private LinkedBlockingQueue<RBTPPacket> ackPackets;
		
		private final ByteBuffer outputBuffer = ByteBuffer.allocateDirect(8 * 1024 * 1024); // 8MB for now
		private long windowFirstSequenceNumber = -1, nextSequenceNumber = -1;
		private int remoteReceiveWindowSize;
		
		private final int MAX_PACKET_SIZE = 1456;
		
		RBTPOutputStreamThread() {
			lastSent = new ArrayList<>();
			ackPackets = new LinkedBlockingQueue<>();
		}
		
		public void init(int remoteReceiveWindowSize, long lastSequenceNum) {
			if(lastSequenceNum == -1) {
				throw new IllegalStateException("OutputStreamThread already initialized.");
			}
			
			this.remoteReceiveWindowSize = remoteReceiveWindowSize;
			this.windowFirstSequenceNumber = lastSequenceNum;
			this.nextSequenceNumber = lastSequenceNum;
		}
		
		public long getNextSequenceNumber() {
			return nextSequenceNumber;
		}
		
		public void acceptAck(RBTPPacket packet) {
			ackPackets.offer(packet);
		}
		
		/**
		 * Write as much data as can fit in the outputBuffer
		 */
		public int write(ByteBuffer data) throws IOException {
			if(nextSequenceNumber == -1) {
				throw new IllegalStateException("OutputStreamThread not initialized.");
			}
			
			if(requestClose || isClosed()) {
				throw new IOException("Socket is closed or closing.");
			}
			
			synchronized(outputBuffer) {
				int writeCount = Math.min(outputBuffer.remaining(), data.remaining());
				for(int i = 0; i < writeCount; i++)
					outputBuffer.put(data.get());
				return writeCount;
			}
		}
		
		@Override
		public void run() {
			int timeoutCount = 0;
			long prevResendTime = 0;
			
			while(true) {
				if(state == RBTPConnectionState.CLOSED) {
					if(PRINT_DEBUG) {
						System.out.println("CONNECTION (OST): Closed, OutputStreamThread exiting.");
					}
					
					break;
				}
				
				synchronized(outputBuffer) {
					if(outputBuffer.position() > 0) {
						if(remoteReceiveWindowSize > 0) {
							if(PRINT_DEBUG) {
								System.out.println("CONNECTION (OST): Sending data, buffer pos: " + outputBuffer.position());
							}
							
							int prevPosition = outputBuffer.position();
							
							outputBuffer.flip();
							
							// Packetize the outputBuffer up to the smaller of data left to write and the remote receive window size.
							int remaining = Math.min(outputBuffer.remaining(), remoteReceiveWindowSize);
							remoteReceiveWindowSize -= remaining;
							
							while(remaining > 0) {
								int payloadSize = Math.min(remaining, MAX_PACKET_SIZE);
								outputBuffer.limit(outputBuffer.position() + payloadSize);
								
								ByteBuffer payload = BufferPool.getBuffer(payloadSize);
								payload.put(outputBuffer);
								payload.flip();
								
								RBTPPacket packet = new RBTPPacket();
								setupPacket(packet, maxWindowSize);
								packet.sequenceNumber((int)nextSequenceNumber);
								packet.payload(payload);
								
								sendPacket.accept(packet);
								lastSent.add(packet);
								
								remaining -= payloadSize;
								nextSequenceNumber = (nextSequenceNumber + payloadSize) & 0xFFFFFFFFL; // limit to 32-bit
							}
							
							outputBuffer.limit(prevPosition);
							outputBuffer.compact();
						}
					} else if(requestClose && (state == RBTPConnectionState.ESTABLISHED || state == RBTPConnectionState.CLOSE_WAIT)) {
						// If all packets are ACK-ed and there is no more data to send, honor requestClose and send the FIN packet
						
						RBTPPacket finPacket = new RBTPPacket();
						setupPacket(finPacket, maxWindowSize);
						finPacket.sequenceNumber((int)nextSequenceNumber);
						finPacket.fin(true);
						sendPacket.accept(finPacket);
						lastSent.add(finPacket);
						
						if(state == RBTPConnectionState.ESTABLISHED) {
							state = RBTPConnectionState.FIN_WAIT_1;
						} else if(state == RBTPConnectionState.CLOSE_WAIT) {
							state = RBTPConnectionState.LAST_ACK;
						}
						
						if(PRINT_DEBUG) {
							System.out.println("CONNECTION (OST): Close requested, sent FIN. seq: " + nextSequenceNumber + ", state: " + state);
						}
					}
				}
				
				try {
					// Poll for any ACK packets
					RBTPPacket packet = ackPackets.poll(TIMEOUT, TimeUnit.MILLISECONDS);
					if(packet == null) {
						// if there are un-ACK-ed packets and the timeout was reached, resend all the packets
						if(lastSent.size() > 0) {
							if(System.currentTimeMillis() - prevResendTime >= TIMEOUT * 2) {
								if(PRINT_DEBUG) {
									System.out.println("CONNECTION (OST): Timeout! Resending " + lastSent.size() + " packets.");
								}
								
								lastSent.forEach(sendPacket); // resend all
								prevResendTime = System.currentTimeMillis();
							}
							
							timeoutCount++;
							if(timeoutCount >= TIMEOUT_COUNT_LIMIT) {
								if(PRINT_DEBUG) {
									System.out.println("CONNECTION (OST): Consecutive timeout count limit reached. Closing...");
								}
								
								state = RBTPConnectionState.CLOSED;
							}
						}
					} else {
						timeoutCount = 0;
						
						if(packet.metadata() == null) {
							if(PRINT_DEBUG) {
								System.out.println("CONNECTION (OST): no metadata ack? " + packet.ack() + ", seq: " + packet.sequenceNumber());
							}
							
							continue;
						}
						
						remoteReceiveWindowSize = packet.receiveWindow() << packet.scale();
						
						// Go through each ACK and remove relevant ones
						for(int i = 0; i < packet.metadata().capacity(); i += 4) {
							long ack = (long)packet.metadata().getInt(i) & 0xFFFFFFFFL;
							
							if(PRINT_DEBUG) {
								System.out.println("CONNECTION (OST): Received ACK on " + ack + //", relativeLoc: " + relativeLoc +
										                   ", windowFirstSeqNum: " + windowFirstSequenceNumber + ", nextSeqNum: " + nextSequenceNumber);
							}
							
							boolean found = false;
							
							for(int j = 0; j < lastSent.size(); j++) {
								if(lastSent.get(j).sequenceNumber() == ack) {
									found = true;
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (OST): Successfully ACK-ed seq " + ack);
									}
									
									RBTPPacket removedPacket = lastSent.remove(j);
									if(removedPacket.fin()) {
										if(state == RBTPConnectionState.FIN_WAIT_1) {
											state = RBTPConnectionState.FIN_WAIT_2;
										} else if(state == RBTPConnectionState.CLOSING) {
											state = RBTPConnectionState.TIMED_WAIT;
										} else if(state == RBTPConnectionState.LAST_ACK) {
											state = RBTPConnectionState.TIMED_WAIT;
										}
										
										if(PRINT_DEBUG) {
											System.out.println("CONNECTION (OST): Received ACK for FIN. Output stream is done! state: " + state);
										}
									}
									removedPacket.destroy();
									break;
								}
							}
							
							if(!found) {
								if(PRINT_DEBUG) {
									System.out.println("CONNECTION (OST): Already acked: " + ack);
								}
							}
						}
						
						// resend whatever is left.
						if(lastSent.size() > 0) {
							if(System.currentTimeMillis() - prevResendTime >= TIMEOUT * 2) {
								lastSent.forEach(sendPacket);
								prevResendTime = System.currentTimeMillis();
							}
						}
						
						packet.destroy();
					}
				}
				catch(Exception exc) {
					exc.printStackTrace();
				}
			}
		}
	}
	
	/**
	 * This class is the master of the RBTPConnection. It reads all packets and feeds ACKs to the OutputStreamThread.
	 */
	private class RBTPInputStreamThread implements Runnable, Consumer<RBTPPacket> {
		private RBTPPacket synFinLastPacket;
		private LinkedBlockingQueue<RBTPPacket> packetsQueue;
		
		private ArrayList<RBTPPacket> packetsReceived;
		private HashMap<Long, Integer> currSequenceNumbers;
		
		private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(8 * 1024 * 1024); // 8MB read buffer for now
		private long readBufferSequenceNum;
		private int windowStartOffset = 0;
		
		private long totalDataReceived = 0;
		
		RBTPInputStreamThread() {
			packetsQueue = new LinkedBlockingQueue<>();
			packetsReceived = new ArrayList<>();
			currSequenceNumbers = new HashMap<>();
		}
		
		@Override
		public void accept(RBTPPacket packet) {
			packetsQueue.offer(packet);
			totalPackets++;
		}
		
		public void init(RBTPPacket initPacket) {
			this.synFinLastPacket = initPacket;
		}
		
		// The challenge consists of computing the SHA1 of the header and having the right number of 0's
		private boolean sha1BeginsWithNZeroes(RBTPPacket packet, byte n) {
			ByteBuffer header = null;
			try {
				MessageDigest digest = MessageDigest.getInstance("SHA-1");
				header = BufferPool.getBuffer(packet.headerSize() * 4);
				header.putShort((short)packet.sourcePort());
				header.putShort((short)packet.destinationPort());
				header.putInt((int)packet.sequenceNumber());
				header.putShort((short)packet.headerSize());
				header.putShort(packet.flags());
				header.putShort((short)0);
				header.putShort((short)packet.receiveWindow());
				for(int i = 0; i < packet.metadata().capacity(); i++) {
					header.put(packet.metadata().get(i));
				}
				header.flip();
				digest.update(header);
				
				byte[] bytes = digest.digest();
				for(int i = 0; i < n; i++) {
					if((bytes[i / 8] & (0x80 >>> (i % 8))) != 0) {
						return false;
					}
				}
				
				return true;
			}
			catch(Exception exc) {
				exc.printStackTrace();
				throw new RuntimeException(exc);
			}
			finally {
				if(header != null) {
					BufferPool.release(header);
				}
			}
		}
		
		private RBTPPacket calculateChallenge(RBTPPacket packet) {
			long randNumber = 0;
			for(int i = 0; i < 7; i++) {
				randNumber |= ((long)packet.metadata().get(i) & 0xFF) << ((6 - i) * 8);
			}
			
			byte n = packet.metadata().get(7);
			
			if(PRINT_DEBUG) {
				System.out.println("CONNECTION (IST): Received SYN-CHA response! RandNum: " + randNumber);
			}
			
			RBTPPacket challengeResponse = new RBTPPacket();
			setupPacket(challengeResponse, maxWindowSize);
			challengeResponse.sequenceNumber((int)(synFinLastPacket.sequenceNumber() + 1));
			challengeResponse.cha(true);
			challengeResponse.ack(true);
			
			ByteBuffer metadata = BufferPool.getBuffer(8);
			challengeResponse.metadata(metadata);
			
			// Keeps trying by incrementing the received random number until the header starts with the required number of zeroes.
			long time = System.nanoTime();
			do {
				for(int i = 0; i < 7; i++) {
					metadata.put(i, (byte)(randNumber >>> ((6 - i) * 8)));
				}
				metadata.put(7, n);
				
				randNumber = (randNumber + 1) & 0xFFFFFFFFFFFFFFL;
			} while(!sha1BeginsWithNZeroes(challengeResponse, n));
			if(PRINT_DEBUG) {
				System.out.println("CONNECTION (IST): Calculate challenge took " + (System.nanoTime() - time) / 1000000 + " ms. New RandNum: " + randNumber);
			}
			
			return challengeResponse;
		}
		
		/**
		 * Main read method: fills the buffer as much as it can and returns the number of bytes read.
		 */
		public int read(ByteBuffer buffer) throws IOException {
			if(windowStartOffset == 0 || buffer.remaining() == 0) {
				if(requestClose || isClosed()) {
					throw new IOException("Socket is closing or closed.");
				}
				
				return 0;
			}
			
			synchronized(readBuffer) {
				readBuffer.clear().limit(windowStartOffset);
				int readCount = Math.min(buffer.remaining(), readBuffer.remaining());
				for(int i = 0; i < readCount; i++)
					buffer.put(readBuffer.get());
				
				readBuffer.limit(readBuffer.capacity());
				readBuffer.compact();
				
				windowStartOffset -= readCount;
				readBufferSequenceNum += readCount;
				
				return readCount;
			}
		}
		
		/**
		 * Goes through all the packets received and sends an ACK packet with the contents.
		 */
		private void ackReceivedPackets() {
			if(packetsReceived.size() > 0) {
				RBTPPacket ackPacket = new RBTPPacket();
				ackPacket.ack(true);
				ackPacket.sequenceNumber((int)outputStreamThread.getNextSequenceNumber()); // doesn't really matter what seqnum is used, it isn't checked anyway
				
				ByteBuffer acks = BufferPool.getBuffer(packetsReceived.size() * 4);
				
				synchronized(readBuffer) {
					if(PRINT_DEBUG) {
						System.out.print("CONNECTION (IST): Sending ack packet, acks: ");
					}
					
					for(RBTPPacket p : packetsReceived) {
						dataPackets++;
						
						if(PRINT_DEBUG) {
							System.out.print(p.sequenceNumber() + ", ");
						}
						
						long relativeLoc = p.sequenceNumber() - readBufferSequenceNum;
						if(relativeLoc < 0) {
							relativeLoc = (int)(0x100000000L + relativeLoc);
						}
						
						// Finds the relative location of the packet with regards to the first byte of the readBuffer
						if(relativeLoc + p.payload().capacity() <= readBuffer.capacity()) {
							acks.putInt((int)p.sequenceNumber());
							
							if(relativeLoc >= windowStartOffset) {
								if(!currSequenceNumbers.containsKey(p.sequenceNumber())) {
									currSequenceNumbers.put(p.sequenceNumber(), p.payload().capacity());
									totalDataReceived += p.payload().capacity();
									
									for(int i = 0; i < p.payload().capacity(); i++) {
										readBuffer.put((int)(relativeLoc + i), p.payload().get(i));
									}
								} else {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Received duplicate packet! Seq: " + p.sequenceNumber() + ", relativeLoc: " + relativeLoc);
									}
									
									duplicateCount++;
								}
								
								if(relativeLoc == windowStartOffset) {
									do {
										long index = readBufferSequenceNum + windowStartOffset;
										windowStartOffset += currSequenceNumbers.get(index);
										currSequenceNumbers.remove(index);
									} while(currSequenceNumbers.containsKey(readBufferSequenceNum + windowStartOffset));
								}
							} else {
								duplicateCount++;
								if(PRINT_DEBUG) {
									System.out.println("CONNECTION (IST): Received old packet! Seq: " + p.sequenceNumber() + ", relativeLoc: " + relativeLoc);
								}
							}
						}
						
						p.destroy();
					}
					if(PRINT_DEBUG) {
						System.out.println();
					}
				}
				
				acks.flip();
				
				int windowSizeLeft = maxWindowSize;
				for(long seq : currSequenceNumbers.keySet()) {
					windowSizeLeft -= currSequenceNumbers.get(seq);
				}
				setupPacket(ackPacket, Math.max(windowSizeLeft, 0));
				
				if(PRINT_DEBUG) {
					System.out.println("CONNECTION (IST): Sending ACK packet, packets not acked: " + currSequenceNumbers.size() + ", windowSizeLeft: " + windowSizeLeft);
				}
				
				ackPacket.metadata(acks);
				
				sendPacket.accept(ackPacket);
				ackPacket.destroy();
				
				packetsReceived.clear();
				
				if(PRINT_DEBUG) {
					System.out.println("CONNECTION (IST): Total data received: " + totalDataReceived + " bytes. Total duplicate packets: " + duplicateCount +
							                   ". Total data packets: " + dataPackets + ", TOTAL packets received: " + totalPackets);
				}
			}
		}
		
		/**
		 * The main engine of the RBTPConnection. This contains the state machine.
		 */
		@Override
		public void run() {
			int timedWaitCount = 0;
			int retryCount = 0;
			long prevReceiveTime = -1;
			
			while(true) {
				try {
					if(state == RBTPConnectionState.CLOSED) {
						if(PRINT_DEBUG) {
							System.out.println("CONNECTION (IST): closed, InputStreamThread exiting.");
						}
						if(synFinLastPacket != null) {
							synFinLastPacket.destroy();
						}
						synFinLastPacket = null;
						break;
					}
					
					if(prevReceiveTime != -1 && System.currentTimeMillis() - prevReceiveTime >= TIMEOUT / 2) {
						ackReceivedPackets();
						prevReceiveTime = -1;
					}
					
					// Receive a packet
					RBTPPacket packet = packetsQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
					
					if(packet == null) {
						if(state == RBTPConnectionState.TIMED_WAIT && ++timedWaitCount >= (2000 / TIMEOUT)) {
							state = RBTPConnectionState.CLOSED;
							continue;
						}
						
						if(state == RBTPConnectionState.SYN_SENT || state == RBTPConnectionState.ACK_CHA_SENT ||
						     state == RBTPConnectionState.SYN_RCVD) {
							if(synFinLastPacket != null) {
								retryCount++;
								if(retryCount >= TIMEOUT_COUNT_LIMIT) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Consecutive timeout limit reached. Closing...");
									}
									state = RBTPConnectionState.CLOSED;
									continue;
								}
								
								if(PRINT_DEBUG) {
									System.out.println("CONNECTION (IST): Timeout! Resending last init/fin packet");
								}
								sendPacket.accept(synFinLastPacket);
							} else {
								if(PRINT_DEBUG) {
									System.out.println("CONNECTION (IST): Init Fin Packet null!");
								}
							}
						}
					} else {
						retryCount = 0;
						
						if(PRINT_DEBUG) {
							System.out.println("CONNECTION (IST): Received packet (seq: " + packet.sequenceNumber() + ")!");
						}
						
						/**
						 * The state machine of the connection. The packet is processed according to the current state.
						 */
						switch(state) {
							case SYN_SENT:
								if(packet.cha() && packet.syn()) {
									if(synFinLastPacket != null) {
										synFinLastPacket.destroy();
									}
									sendPacket.accept(synFinLastPacket = calculateChallenge(packet));
									state = RBTPConnectionState.ACK_CHA_SENT;
								} else {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Received invalid packet, expected SYN-CHA. Closing...");
									}
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case SYN_RCVD:
								if(packet.syn()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Re-received SYN, resending SYN-CHA.");
									}
									sendPacket.accept(synFinLastPacket);
								} else if(packet.ack() && packet.cha()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Received ACK-CHA.");
									}
									
									if(sha1BeginsWithNZeroes(packet, packet.metadata().get(7))) {
										if(PRINT_DEBUG) {
											System.out.println("Connection: client passed challenge, connection established!");
										}
										
										RBTPPacket ackPacket = new RBTPPacket();
										setupPacket(ackPacket, maxWindowSize);
										ackPacket.sequenceNumber((int)(synFinLastPacket.sequenceNumber() + 1));
										ackPacket.ack(true);
										sendPacket.accept(ackPacket);
										
										state = RBTPConnectionState.ESTABLISHED;
										
										readBufferSequenceNum = packet.sequenceNumber();
										outputStreamThread.init(packet.receiveWindow() << packet.scale(), ackPacket.sequenceNumber());
										
										if(synFinLastPacket != null) {
											synFinLastPacket.destroy();
										}
										
										synFinLastPacket = ackPacket;
									} else {
										if(PRINT_DEBUG) {
											System.out.println("CONNECTION (IST): client failed challenge, connection rejected!");
										}
										
										RBTPPacket rejPacket = new RBTPPacket();
										setupPacket(rejPacket, maxWindowSize);
										rejPacket.sequenceNumber((int)(synFinLastPacket.sequenceNumber() + 1));
										rejPacket.rej(true);
										sendPacket.accept(rejPacket);
										
										state = RBTPConnectionState.TIMED_WAIT;
										
										if(synFinLastPacket != null) {
											synFinLastPacket.destroy();
										}
										
										synFinLastPacket = rejPacket;
									}
								} else {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Received invalid packet, expected ACK-CHA. Closing...");
									}
									
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case ACK_CHA_SENT:
								if(packet.syn() && packet.cha()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Re-received SYN-CHA, resending ACK-CHA.");
									}
									
									sendPacket.accept(synFinLastPacket);
								} else if(packet.ack()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Server accepted challenge, connection established!");
									}
									
									state = RBTPConnectionState.ESTABLISHED;
									
									readBufferSequenceNum = packet.sequenceNumber();
									outputStreamThread.init(packet.receiveWindow() << packet.scale(), synFinLastPacket.sequenceNumber());
									
									if(synFinLastPacket != null) {
										synFinLastPacket.destroy();
									}
									synFinLastPacket = null;
								} else if(packet.rej()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Server declined challenge, connection rejected!");
									}
									
									state = RBTPConnectionState.CLOSED;
									if(synFinLastPacket != null) {
										synFinLastPacket.destroy();
									}
									synFinLastPacket = null;
								} else {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Received invalid packet, expected ACK/REJ. Closing...");
									}
									
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case ESTABLISHED:
							case FIN_WAIT_1:
							case FIN_WAIT_2:
							case CLOSING:
							case CLOSE_WAIT:
							case LAST_ACK:
								if(packet.cha() && packet.ack()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): RE-received ACK-CHA, resending ACK.");
									}
									
									sendPacket.accept(synFinLastPacket);
									
									packet.destroy();
								} else if(packet.ack()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Received ACK packet. seq: " + packet.sequenceNumber());
									}
									
									outputStreamThread.acceptAck(packet);
								} else if(packet.fin()) {
									RBTPPacket finAckPacket = new RBTPPacket();
									setupPacket(finAckPacket, maxWindowSize);
									finAckPacket.sequenceNumber((int)outputStreamThread.getNextSequenceNumber());
									finAckPacket.ack(true);
									ByteBuffer metadata = BufferPool.getBuffer(4);
									metadata.putInt((int)packet.sequenceNumber());
									finAckPacket.metadata(metadata);
									
									if(synFinLastPacket != null) {
										synFinLastPacket.destroy();
									}
									synFinLastPacket = finAckPacket;
									
									if(state == RBTPConnectionState.ESTABLISHED) {
										state = RBTPConnectionState.CLOSE_WAIT;
									} else if(state == RBTPConnectionState.FIN_WAIT_1) {
										state = RBTPConnectionState.CLOSING;
									} else if(state == RBTPConnectionState.FIN_WAIT_2) {
										state = RBTPConnectionState.TIMED_WAIT;
									}
									
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Received FIN packet. seq: " + packet.sequenceNumber() + ". state: " + state);
									}
									
									sendPacket.accept(finAckPacket);
									
									requestClose = true;
									
									packet.destroy();
								} else if(packet.rej()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Somehow received REJ after connection established?");
									}
									
									state = RBTPConnectionState.CLOSED;
									packet.destroy();
								} else if(packet.rst()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): RST flag unimplemented! Closing connection.");
									}
									
									state = RBTPConnectionState.CLOSED;
									packet.destroy();
								} else if(packet.syn()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Somehow received SYN after connection established?");
									}
									
									state = RBTPConnectionState.CLOSED;
									packet.destroy();
								} else {
									// no flags are set
									if(!packetsReceived.contains(packet)) {
										if(PRINT_DEBUG) {
											System.out.println("CONNECTION (IST): Received data packet, seq: " + packet.sequenceNumber() + ", payload len: " + packet.payload().capacity());
										}
										
										packetsReceived.add(packet);
										prevReceiveTime = System.currentTimeMillis();
									} else {
										duplicateCount++;
										dataPackets++;
										if(PRINT_DEBUG) {
											System.out.println("CONNECTION (IST): Received duplicate packet!");
										}
										
										packet.destroy();
									}
								}
								
								break;
							case TIMED_WAIT:
								timedWaitCount = 0;
								
								if(packet.fin() && synFinLastPacket != null) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Re-received FIN, resending ACK.");
									}
									
									sendPacket.accept(synFinLastPacket);
								} else if(packet.ack() && packet.cha()) {
									if(PRINT_DEBUG) {
										System.out.println("CONNECTION (IST): Re-received ACK-CHA, resending REJ.");
									}
									
									sendPacket.accept(synFinLastPacket);
								}
								
								packet.destroy();
								
								break;
						}
					}
				}
				catch(Exception exc) {
					exc.printStackTrace();
					state = RBTPConnectionState.CLOSED;
				}
			}
		}
	}
}
