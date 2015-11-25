package edu.rbtp.impl;

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
 * @author Roi Atalla
 */
public class RBTPConnection implements Bindable {
	private enum RBTPConnectionState {
		CLOSED, SYN_RCVD, SYN_SENT, ACK_CHA_SENT, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2,
		CLOSING, TIMED_WAIT, CLOSE_WAIT, LAST_ACK
	}
	
	private volatile RBTPConnectionState state;
	private int maxWindowSize = 50000;
	private final long TIMEOUT = 200;
	
	public int duplicateCount = 0;
	public int dataPackets = 0;
	public int totalPackets = 0;
	
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
		if(this.bindingInterface != null)
			throw new IllegalStateException("Already bound.");
		
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
	
	public synchronized void connect(RBTPSocketAddress address) throws IOException {
		if(remoteAddress != null)
			throw new IllegalStateException("Already connected.");
		
		if(bindingInterface == null)
			throw new IllegalStateException("Socket not bound.");
		
		remoteAddress = address;
		
		state = RBTPConnectionState.SYN_SENT;
		RBTPPacket synPacket = new RBTPPacket();
		setupPacket(synPacket, maxWindowSize);
		synPacket.sequenceNumber(rng.nextInt());
		synPacket.syn(true);
		
		sendPacket.accept(synPacket);
		
		inputStreamThread.init(synPacket);
		
		startNetworkThreads();
		
		while(state != RBTPConnectionState.ESTABLISHED && state != RBTPConnectionState.CLOSED) {
			try {
				Thread.sleep(10);
			}
			catch(InterruptedException exc) {}
		}
		
		if(state != RBTPConnectionState.ESTABLISHED) {
			close();
			throw new IOException("Connect failed. state: " + state);
		}
	}
	
	public void accept(RBTPPacket synPacket) {
		state = RBTPConnectionState.SYN_RCVD;
		
		remoteAddress = synPacket.address;
		
		RBTPPacket chaPacket = new RBTPPacket();
		setupPacket(chaPacket, this.maxWindowSize);
		chaPacket.sequenceNumber(rng.nextInt());
		chaPacket.syn(true);
		chaPacket.cha(true);
		ByteBuffer metadata = BufferPool.getBuffer(8);
		long randValue = rng.nextLong() & 0xFFFFFFFFFFFFFFL;
		for(int i = 6; i >= 0; i--) {
			metadata.put((byte)(randValue >>> (i * 8)));
		}
		metadata.put((byte)13); // 13 zeroes for now
		metadata.flip();
		chaPacket.metadata(metadata);
		
		sendPacket.accept(chaPacket);
		
		inputStreamThread.init(chaPacket);
		
		startNetworkThreads();
		
		System.out.println("SERVER: Accepted SYN, sending SYN-CHA, seq: " + chaPacket.sequenceNumber() + ", RandNum: " + randValue);
	}
	
	private void startNetworkThreads() {
		Thread ost = new Thread(outputStreamThread);
		ost.setName("RBTP Output Stream Thread port: " + bindingInterface.getPort());
		ost.start();
		
		Thread ist = new Thread(inputStreamThread);
		ist.setName("RBTP Input Stream Thread port: " + bindingInterface.getPort());
		ist.start();
		
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
			
			System.out.println("CONNECTION: Unbinding.");
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
	
	public int read(ByteBuffer data, boolean block) {
		if(block) {
			int read;
			while((read = inputStreamThread.read(data)) == 0 && !isClosed()) {
				System.out.println("READ: Waiting for data to read...");
				try {
					Thread.sleep(100);
				} catch(Exception exc) {}
			}
			return read;
		} else {
			return inputStreamThread.read(data);
		}
	}
	
	public int write(ByteBuffer data, boolean block) {
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
			if(lastSequenceNum == -1)
				throw new IllegalStateException("OutputStreamThread already initialized.");
			
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
		
		public int write(ByteBuffer data) {
			if(nextSequenceNumber == -1)
				throw new IllegalStateException("OutputStreamThread not initialized.");
			
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
					System.out.println("CONNECTION (OST): Closed, OutputStreamThread exiting.");
					break;
				}
				
				if(lastSent.size() == 0) {
					synchronized(outputBuffer) {
						if(outputBuffer.position() > 0) {
							if(remoteReceiveWindowSize > 0) {
								System.out.println("CONNECTION (OST): Sending data, buffer pos: " + outputBuffer.position());
								int prevPosition = outputBuffer.position();
								
								outputBuffer.flip();
								
								int remaining = Math.min(outputBuffer.remaining(), remoteReceiveWindowSize);
								remoteReceiveWindowSize -= remaining;
								
								while(remaining > 0) {
									int payloadSize = Math.min(remaining, MAX_PACKET_SIZE);
									outputBuffer.limit(outputBuffer.position() + payloadSize);
									ByteBuffer payload = outputBuffer.slice();
									
									RBTPPacket packet = new RBTPPacket();
									setupPacket(packet, maxWindowSize);
									packet.sequenceNumber((int)nextSequenceNumber);
									packet.payload(payload);
									
									sendPacket.accept(packet);
									lastSent.add(packet);
									
									remaining -= payloadSize;
									nextSequenceNumber = (nextSequenceNumber + payloadSize) & 0xFFFFFFFFL; // limit to 32-bit
									outputBuffer.position(outputBuffer.position() + payloadSize);
								}
								
								outputBuffer.limit(outputBuffer.capacity());
								outputBuffer.position(prevPosition);
							}
						} else if(requestClose && (state == RBTPConnectionState.ESTABLISHED || state == RBTPConnectionState.CLOSE_WAIT)) {
							RBTPPacket finPacket = new RBTPPacket();
							setupPacket(finPacket, maxWindowSize);
							finPacket.sequenceNumber((int)nextSequenceNumber);
							finPacket.fin(true);
							sendPacket.accept(finPacket);
							lastSent.add(finPacket);
							
							if(state == RBTPConnectionState.ESTABLISHED)
								state = RBTPConnectionState.FIN_WAIT_1;
							else if(state == RBTPConnectionState.CLOSE_WAIT)
								state = RBTPConnectionState.LAST_ACK;
							
							System.out.println("CONNECTION (OST): Close requested, sent FIN. seq: " + nextSequenceNumber + ", state: " + state);
						}
					}
				}
				
				try {
					RBTPPacket packet = ackPackets.poll(TIMEOUT, TimeUnit.MILLISECONDS);
					if(packet == null) {
						if(lastSent.size() > 0) {
							timeoutCount++;
							if(timeoutCount >= 30) {
								System.out.println("CONNECTION (OST): Consecutive timeout count limit reached. Closing...");
								state = RBTPConnectionState.CLOSED;
								continue;
							}
							
							if(System.currentTimeMillis() - prevResendTime >= TIMEOUT * 2) {
								System.out.println("CONNECTION (OST): Timeout! Resending " + lastSent.size() + " packets.");
								lastSent.forEach(sendPacket); // resend all
								prevResendTime = System.currentTimeMillis();
							}
						}
					} else {
						timeoutCount = 0;
						
						if(packet.metadata() == null) {
							System.out.println("CONNECTION (OST): no metadata ack? " + packet.ack() + ", seq: " + packet.sequenceNumber());
							continue;
						}
						
						remoteReceiveWindowSize = packet.receiveWindow() << packet.scale();
						
						long totalSent = nextSequenceNumber - windowFirstSequenceNumber;
						if(totalSent < 0)
							totalSent = (0x100000000L + totalSent) & 0xFFFFFFFFL;
						
						for(int i = 0; i < packet.metadata().capacity(); i += 4) {
							long ack = (long)packet.metadata().getInt(i) & 0xFFFFFFFFL;
							
							long relativeLoc = ack - windowFirstSequenceNumber;
							if(relativeLoc < 0)
								relativeLoc = (int)(0x100000000L + relativeLoc);
							
							System.out.println("CONNECTION (OST): Received ACK on " + ack + ", relativeLoc: " + relativeLoc + ", windowFirstSeqNum: " + windowFirstSequenceNumber + ", nextSeqNum: " + nextSequenceNumber);
							
							if(relativeLoc == 0 || relativeLoc >= 0 && relativeLoc < totalSent) {
								boolean found = false;
								
								for(int j = 0; j < lastSent.size(); j++) {
									if(lastSent.get(j).sequenceNumber() == ack) {
										found = true;
										System.out.println("CONNECTION (OST): Successfully ACK-ed seq " + ack);
										RBTPPacket removedPacket = lastSent.remove(j);
										if(removedPacket.fin()) {
											if(state == RBTPConnectionState.FIN_WAIT_1)
												state = RBTPConnectionState.FIN_WAIT_2;
											else if(state == RBTPConnectionState.CLOSING)
												state = RBTPConnectionState.TIMED_WAIT;
											else if(state == RBTPConnectionState.LAST_ACK)
												state = RBTPConnectionState.TIMED_WAIT;
											
											System.out.println("CONNECTION (OST): Received ACK for FIN. Output stream is done! state: " + state);
										}
										removedPacket.destroy();
										break;
									}
								}
								
								if(!found) {
									System.out.println("CONNECTION (OST): Already acked: " + ack + ", relativeLoc: " + relativeLoc + ", totalSent: " + totalSent);
								}
							}
						}
						
						if(lastSent.size() > 0) {
							if(System.currentTimeMillis() - prevResendTime >= TIMEOUT * 2) {
								lastSent.forEach(sendPacket);
								prevResendTime = System.currentTimeMillis();
							}
						} else {
							synchronized(outputBuffer) {
								windowFirstSequenceNumber = nextSequenceNumber;
								
								outputBuffer.flip();
								try {
									outputBuffer.position((int)totalSent);
								} catch(Exception exc) {
									exc.printStackTrace();
									System.out.println("CONNECTION (OST): exception while setting outputBuffer position to " + totalSent);
								}
								outputBuffer.compact();
							}
						}
						
						packet.destroy();
					}
				} catch(Exception exc) {
					exc.printStackTrace();
				}
			}
		}
	}
	
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
					if((bytes[i / 8] & (0x80 >>> (i%8))) != 0) {
						return false;
					}
				}
				
				return true;
			} catch(Exception exc) {
				exc.printStackTrace();
				throw new RuntimeException(exc);
			}
			finally {
				if(header != null)
					BufferPool.release(header);
			}
		}
		
		private RBTPPacket calculateChallenge(RBTPPacket packet) {
			long randNumber = 0;
			for(int i = 0; i < 7; i++) {
				randNumber |= ((long)packet.metadata().get(i) & 0xFF) << ((6 - i) * 8);
			}
			
			byte n = packet.metadata().get(7);
			
			System.out.println("CONNECTION (IST): Received SYN-CHA response! RandNum: " + randNumber);
			
			RBTPPacket challengeResponse = new RBTPPacket();
			setupPacket(challengeResponse, maxWindowSize);
			challengeResponse.sequenceNumber((int)(synFinLastPacket.sequenceNumber() + 1));
			challengeResponse.cha(true);
			challengeResponse.ack(true);
			
			ByteBuffer metadata = BufferPool.getBuffer(8);
			challengeResponse.metadata(metadata);
			
			long time = System.nanoTime();
			do {
				for(int i = 0; i < 7; i++) {
					metadata.put(i, (byte)(randNumber >>> ((6 - i) * 8)));
				}
				metadata.put(7, n);
				
				randNumber = (randNumber + 1) & 0xFFFFFFFFFFFFFFL;
			} while(!sha1BeginsWithNZeroes(challengeResponse, n));
			System.out.println("CONNECTION (IST): Calculate challenge took " + (System.nanoTime() - time) / 1000000 + " ms. New RandNum: " + randNumber);
			
			return challengeResponse;
		}
		
		public int read(ByteBuffer buffer) {
			if(windowStartOffset == 0 || buffer.remaining() == 0) {
				if(requestClose || isClosed())
					throw new IllegalStateException("Socket is closing or closed.");
				
				return 0;
			}
			
			synchronized(readBuffer) {
				readBuffer.clear().limit(windowStartOffset);
				int readCount = Math.min(buffer.remaining(), readBuffer.remaining());
				for(int i = 0; i < readCount; i++)
					buffer.put(readBuffer.get());
				
				readBuffer.limit(windowStartOffset + maxWindowSize);
				readBuffer.compact();
				
				windowStartOffset -= readCount;
				readBufferSequenceNum += readCount;
				
				System.out.println("READ: WindowStartOffset is now " + windowStartOffset);
				
				return readCount;
			}
		}
		
		private void ackReceivedPackets() {
			if(packetsReceived.size() > 0) {
				RBTPPacket ackPacket = new RBTPPacket();
				ackPacket.ack(true);
				ackPacket.sequenceNumber((int)outputStreamThread.getNextSequenceNumber()); // doesn't really matter what seqnum is used, it isn't checked anyway
				
				ByteBuffer acks = BufferPool.getBuffer(packetsReceived.size() * 4);
				
				synchronized(readBuffer) {
					System.out.print("CONNECTION (IST): Sending ack packet, acks: ");
					for(RBTPPacket p : packetsReceived) {
						dataPackets++;
						
						System.out.print(p.sequenceNumber() + ", ");
						
						long relativeLoc = p.sequenceNumber() - readBufferSequenceNum;
						if(relativeLoc < 0)
							relativeLoc = (int)(0x100000000L + relativeLoc);
						
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
									System.out.println("CONNECTION (IST): Received duplicate packet! Seq: " + p.sequenceNumber() + ", relativeLoc: " + relativeLoc);
									duplicateCount++;
								}
								
								if(relativeLoc == windowStartOffset) {
									do {
										long index = readBufferSequenceNum + windowStartOffset;
										windowStartOffset += currSequenceNumbers.get(index);
										System.out.println("CONNECTION (IST): windowStartOffset is now " + windowStartOffset);
										currSequenceNumbers.remove(index);
									} while(currSequenceNumbers.containsKey(readBufferSequenceNum + windowStartOffset));
								}
							} else {
								duplicateCount++;
								System.out.println("CONNECTION (IST): Received old packet! Seq: " + p.sequenceNumber() + ", relativeLoc: " + relativeLoc);
							}
						}
						
						p.destroy();
					}
					System.out.println();
				}
				
				acks.flip();
				
				int windowSizeLeft = maxWindowSize;
				for(int s : currSequenceNumbers.values()) {
					maxWindowSize += s;
				}
				setupPacket(ackPacket, windowSizeLeft);
				
				System.out.println("CONNECTION (IST): Sending ACK packet, windowSizeLeft: " + windowSizeLeft);
				
				ackPacket.metadata(acks);
				
				sendPacket.accept(ackPacket);
				ackPacket.destroy();
				
				packetsReceived.clear();
				
				System.out.println("CONNECTION (IST): Total data received: " + totalDataReceived + " bytes. Total duplicate packets: " + duplicateCount +
						                   ". Total data packets: " + dataPackets + ", TOTAL packets received: " + totalPackets);
			}
		}
		
		@Override
		public void run() {
			int timedWaitCount = 0;
			
			long prevReceiveTime = -1;
			
			while(true) {
				try {
					if(state == RBTPConnectionState.CLOSED) {
						System.out.println("CONNECTION (IST): closed, InputStreamThread exiting.");
						if(synFinLastPacket != null)
							synFinLastPacket.destroy();
						synFinLastPacket = null;
						break;
					}
					
					if(prevReceiveTime != -1 && System.currentTimeMillis() - prevReceiveTime >= TIMEOUT) {
						ackReceivedPackets();
						prevReceiveTime = -1;
					}
					
					RBTPPacket packet = packetsQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
					
					if(packet == null) {
						if(state == RBTPConnectionState.TIMED_WAIT && ++timedWaitCount >= 20) {
							state = RBTPConnectionState.CLOSED;
							continue;
						}
						
						if(state == RBTPConnectionState.SYN_SENT || state == RBTPConnectionState.ACK_CHA_SENT || 
								state == RBTPConnectionState.SYN_RCVD) {
							if(synFinLastPacket != null) {
								System.out.println("CONNECTION (IST): Timeout! Resending last init/fin packet");
								sendPacket.accept(synFinLastPacket);
							}
							else {
								System.out.println("CONNECTION (IST): Init Fin Packet null!");
							}
						}
					}
					else {
						System.out.println("CONNECTION (IST): Received packet (seq: " + packet.sequenceNumber() + ")!");
						
						switch(state) {
							case SYN_SENT:
								if(packet.cha() && packet.syn()) {
									if(synFinLastPacket != null)
										synFinLastPacket.destroy();
									sendPacket.accept(synFinLastPacket = calculateChallenge(packet));
									state = RBTPConnectionState.ACK_CHA_SENT;
								} else {
									System.out.println("CONNECTION (IST): Received invalid packet, expected SYN-CHA. Closing...");
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case SYN_RCVD:
								if(packet.syn()) {
									System.out.println("CONNECTION (IST): Re-received SYN, resending SYN-CHA.");
									sendPacket.accept(synFinLastPacket);
								} else if(packet.ack() && packet.cha()) {
									System.out.println("CONNECTION (IST): Received ACK-CHA.");
									if(sha1BeginsWithNZeroes(packet, packet.metadata().get(7))) {
										System.out.println("Connection: client passed challenge, connection established!");
										
										RBTPPacket ackPacket = new RBTPPacket();
										setupPacket(ackPacket, maxWindowSize);
										ackPacket.sequenceNumber((int)(synFinLastPacket.sequenceNumber() + 1));
										ackPacket.ack(true);
										sendPacket.accept(ackPacket);
										
										state = RBTPConnectionState.ESTABLISHED;
										
										readBufferSequenceNum = packet.sequenceNumber();
										outputStreamThread.init(packet.receiveWindow() << packet.scale(), ackPacket.sequenceNumber());
										
										if(synFinLastPacket != null)
											synFinLastPacket.destroy();
										
										synFinLastPacket = ackPacket;
									} else {
										System.out.println("CONNECTION (IST): client failed challenge, connection rejected!");
										RBTPPacket rejPacket = new RBTPPacket();
										setupPacket(rejPacket, maxWindowSize);
										rejPacket.sequenceNumber((int)(synFinLastPacket.sequenceNumber() + 1));
										rejPacket.rej(true);
										sendPacket.accept(rejPacket);
										
										state = RBTPConnectionState.TIMED_WAIT;
										
										if(synFinLastPacket != null)
											synFinLastPacket.destroy();
										
										synFinLastPacket = rejPacket;
									}
								} else {
									System.out.println("CONNECTION (IST): Received invalid packet, expected ACK-CHA. Closing...");
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case ACK_CHA_SENT:
								if(packet.syn() && packet.cha()) {
									System.out.println("CONNECTION (IST): Re-received SYN-CHA, resending ACK-CHA.");
									sendPacket.accept(synFinLastPacket);
								} else if(packet.ack()) {
									System.out.println("CONNECTION (IST): Server accepted challenge, connection established!");
									state = RBTPConnectionState.ESTABLISHED;
									
									readBufferSequenceNum = packet.sequenceNumber();
									outputStreamThread.init(packet.receiveWindow() << packet.scale(), synFinLastPacket.sequenceNumber());
									
									if(synFinLastPacket != null)
										synFinLastPacket.destroy();
									synFinLastPacket = null;
								} else if(packet.rej()) {
									System.out.println("CONNECTION (IST): Server declined challenge, connection rejected!");
									state = RBTPConnectionState.CLOSED;
									if(synFinLastPacket != null)
										synFinLastPacket.destroy();
									synFinLastPacket = null;
								} else {
									System.out.println("CONNECTION (IST): Received invalid packet, expected ACK/REJ. Closing...");
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
									System.out.println("CONNECTION (IST): RE-received ACK-CHA, resending ACK.");
									sendPacket.accept(synFinLastPacket);
									
									packet.destroy();
								} else if(packet.ack()) {
									System.out.println("CONNECTION (IST): Received ACK packet. seq: " + packet.sequenceNumber());
									outputStreamThread.acceptAck(packet);
								} else if(packet.fin()) {
									RBTPPacket finAckPacket = new RBTPPacket();
									setupPacket(finAckPacket, maxWindowSize);
									finAckPacket.sequenceNumber((int)outputStreamThread.getNextSequenceNumber());
									finAckPacket.ack(true);
									ByteBuffer metadata = BufferPool.getBuffer(4);
									metadata.putInt((int)packet.sequenceNumber());
									finAckPacket.metadata(metadata);
									
									if(synFinLastPacket != null)
										synFinLastPacket.destroy();
									synFinLastPacket = finAckPacket;
									
									if(state == RBTPConnectionState.ESTABLISHED)
										state = RBTPConnectionState.CLOSE_WAIT;
									else if(state == RBTPConnectionState.FIN_WAIT_1)
										state = RBTPConnectionState.CLOSING;
									else if(state == RBTPConnectionState.FIN_WAIT_2)
										state = RBTPConnectionState.TIMED_WAIT;
									
									System.out.println("CONNECTION (IST): Received FIN packet. seq: " + packet.sequenceNumber() + ". state: " + state);
									
									sendPacket.accept(finAckPacket);
									
									requestClose = true;
								} else if(packet.rej()) {
									System.out.println("CONNECTION (IST): Somehow received REJ after connection established?");
									state = RBTPConnectionState.CLOSED;
									packet.destroy();
								} else if(packet.rst()) {
									System.out.println("CONNECTION (IST): RST flag unimplemented! Closing connection.");
									state = RBTPConnectionState.CLOSED;
									packet.destroy();
								} else if(packet.syn()) {
									System.out.println("CONNECTION (IST): Somehow received SYN after connection established?");
									state = RBTPConnectionState.CLOSED;
									packet.destroy();
								} else {
									// no flags are set
									if(!packetsReceived.contains(packet)) {
										System.out.println("CONNECTION (IST): Received data packet, seq: " + packet.sequenceNumber() + ", payload len: " + packet.payload().capacity());
										packetsReceived.add(packet);
										prevReceiveTime = System.currentTimeMillis();
									}
									else {
										duplicateCount++;
										dataPackets++;
										System.out.println("CONNECTION (IST): Received duplicate packet!");
										packet.destroy();
									}
								}
								
								break;
							case TIMED_WAIT:
								timedWaitCount = 0;
								
								if(packet.fin() && synFinLastPacket != null) {
									System.out.println("CONNECTION (IST): Re-received FIN, resending ACK.");
									sendPacket.accept(synFinLastPacket);
								} else if(packet.ack() && packet.cha()) {
									System.out.println("CONNECTION (IST): Re-received ACK-CHA, resending REJ.");
									sendPacket.accept(synFinLastPacket);
								}
								
								packet.destroy();
								
								break;
						}
					}
				} catch(Exception exc) {
					exc.printStackTrace();
					state = RBTPConnectionState.CLOSED;
				}
			}
		}
	}
}
