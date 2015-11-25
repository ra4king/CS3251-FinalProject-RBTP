package edu.rbtp.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
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
	private int windowCapacity = 100000;
	private final long TIMEOUT = 200;
	
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
	
	private static Random rng = new Random();
	
	public synchronized void connect(RBTPSocketAddress address) throws IOException {
		if(remoteAddress != null)
			throw new IllegalStateException("Already connected.");
		
		if(bindingInterface == null)
			throw new IllegalStateException("Socket not bound.");
		
		remoteAddress = address;
		
		state = RBTPConnectionState.SYN_SENT;
		RBTPPacket synPacket = new RBTPPacket();
		setupPacket(synPacket, windowCapacity);
		synPacket.sequenceNumber(rng.nextInt());
		synPacket.syn(true);
		
		sendPacket.accept(synPacket);
		
		inputStreamThread.init(synPacket);
		
		startNetworkThreads();
		
		while(state != RBTPConnectionState.ESTABLISHED && state != RBTPConnectionState.CLOSED) {
			try {
				Thread.sleep(100);
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
		setupPacket(chaPacket, this.windowCapacity);
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
		return 0;
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
			
			while(true) {
				if(state == RBTPConnectionState.CLOSED) {
					System.out.println("CONNECTION (OST): Closed, OutputStreamThread exiting.");
					break;
				}
				
				if(lastSent.size() == 0) {
					synchronized(outputBuffer) {
						if(outputBuffer.position() > 0 && remoteReceiveWindowSize > 0) {
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
								setupPacket(packet, windowCapacity);
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
					}
				}
				
				try {
					RBTPPacket packet = ackPackets.poll(TIMEOUT, TimeUnit.MILLISECONDS);
					if(packet == null) {
						if(lastSent.size() > 0) {
							timeoutCount++;
							if(timeoutCount >= 100) {
								System.out.println("CONNECTION (OST): Consecutive timeout count limit reached. Closing...");
								state = RBTPConnectionState.CLOSED;
								continue;
							}
							
							System.out.println("CONNECTION (OST): Timeout! Resending " + lastSent.size() + " packets.");
							lastSent.forEach(sendPacket); // resend all
						}
					} else {
						timeoutCount = 0;
						
						if(packet.metadata() == null) {
							System.out.println("CONNECTION (OST): wtf... ack? " + packet.ack() + ", seq: " + packet.sequenceNumber());
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
							
							if(relativeLoc >= 0 && relativeLoc < totalSent) {
								boolean found = false;
								
								for(int j = 0; j < lastSent.size(); j++) {
									if(lastSent.get(j).sequenceNumber() == ack) {
										found = true;
										System.out.println("CONNECTION (OST): Successfully ACK-ed seq " + ack);
										lastSent.remove(j).destroy();
										break;
									}
								}
								
								if(!found) {
									System.out.println("CONNECTION (OST): Already acked: " + ack + ", relativeLoc: " + relativeLoc + ", totalSent: " + totalSent);
								}
							}
						}
						
						if(lastSent.size() > 0) {
							lastSent.forEach(sendPacket);
						} else {
							synchronized(outputBuffer) {
								windowFirstSequenceNumber = nextSequenceNumber;
								
								outputBuffer.flip();
								outputBuffer.position((int)totalSent);
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
		private RBTPPacket synFinlastPacket;
		private LinkedBlockingQueue<RBTPPacket> packetsQueue;
		
		private ArrayList<RBTPPacket> packetsReceived;
		
		RBTPInputStreamThread() {
			packetsQueue = new LinkedBlockingQueue<>();
			packetsReceived = new ArrayList<>();
		}
		
		@Override
		public void accept(RBTPPacket packet) {
			packetsQueue.offer(packet);
		}
		
		public void init(RBTPPacket initPacket) {
			this.synFinlastPacket = initPacket;
		}
		
		private boolean sha1BeginsWithNZeroes(RBTPPacket packet, byte n, boolean print) {
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
			setupPacket(challengeResponse, windowCapacity);
			challengeResponse.sequenceNumber((int)(synFinlastPacket.sequenceNumber() + 1));
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
			} while(!sha1BeginsWithNZeroes(challengeResponse, n, false));
			System.out.println("CONNECTION (IST): Calculate challenge took " + (System.nanoTime() - time) / 1000000 + " ms. New RandNum: " + randNumber);
			
			return challengeResponse;
		}
		
		private void ackReceivedPackets() {
			if(packetsReceived.size() > 0) {
				RBTPPacket ackPacket = new RBTPPacket();
				setupPacket(ackPacket, windowCapacity);
				ackPacket.ack(true);
				ackPacket.sequenceNumber((int)outputStreamThread.getNextSequenceNumber()); // doesn't really matter what seqnum is used, it isn't checked anyway
				
				ByteBuffer acks = BufferPool.getBuffer(packetsReceived.size() * 4);
				
				System.out.print("CONNECTION (IST): Sending ack packet, acks: ");
				for(RBTPPacket p : packetsReceived) {
					System.out.print(p.sequenceNumber() + ", ");
					acks.putInt((int)p.sequenceNumber());
					p.destroy(); // TODO: don't destroy... send back to user
				}
				System.out.println();
				
				acks.flip();
				
				ackPacket.metadata(acks);
				
				sendPacket.accept(ackPacket);
				ackPacket.destroy();
				
				packetsReceived.clear();
			}
		}
		
		@Override
		public void run() {
			int timedWaitCount = 0;
			
			long prevTime = -1;
			
			while(true) {
				try {
					if(state == RBTPConnectionState.CLOSED) { // TODO: not the right way to handle close, need to wait for all data to finish coming in
						System.out.println("CONNECTION (IST): closed, InputStreamThread exiting.");
						if(synFinlastPacket != null)
							synFinlastPacket.destroy();
						synFinlastPacket = null;
						break;
					}
					
					if(prevTime != -1 && System.currentTimeMillis() - prevTime >= 100) {
						ackReceivedPackets();
						prevTime = -1;
					}
					
					RBTPPacket packet = packetsQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
					
					if(packet == null) {
						if(state == RBTPConnectionState.TIMED_WAIT && ++timedWaitCount >= 10) {
							state = RBTPConnectionState.CLOSED;
							continue;
						}
						
						if(state == RBTPConnectionState.SYN_SENT || state == RBTPConnectionState.ACK_CHA_SENT || 
								state == RBTPConnectionState.SYN_RCVD) {
							if(synFinlastPacket != null) {
								System.out.println("CONNECTION (IST): Timeout! Resending last init/fin packet");
								sendPacket.accept(synFinlastPacket);
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
									if(synFinlastPacket != null)
										synFinlastPacket.destroy();
									sendPacket.accept(synFinlastPacket = calculateChallenge(packet));
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
									sendPacket.accept(synFinlastPacket);
								} else if(packet.ack() && packet.cha()) {
									System.out.println("CONNECTION (IST): Received ACK-CHA.");
									if(sha1BeginsWithNZeroes(packet, packet.metadata().get(7), true)) {
										System.out.println("Connection: client passed challenge, connection established!");
										
										RBTPPacket ackPacket = new RBTPPacket();
										setupPacket(ackPacket, windowCapacity);
										ackPacket.sequenceNumber((int)(synFinlastPacket.sequenceNumber() + 1));
										ackPacket.ack(true);
										sendPacket.accept(ackPacket);
										
										state = RBTPConnectionState.ESTABLISHED;
										
										outputStreamThread.init(packet.receiveWindow() << packet.scale(), synFinlastPacket.sequenceNumber());
										
										if(synFinlastPacket != null)
											synFinlastPacket.destroy();
										
										synFinlastPacket = ackPacket;
									} else {
										System.out.println("CONNECTION (IST): client failed challenge, connection rejected!");
										RBTPPacket rejPacket = new RBTPPacket();
										setupPacket(rejPacket, windowCapacity);
										rejPacket.sequenceNumber((int)(synFinlastPacket.sequenceNumber() + 1));
										rejPacket.rej(true);
										sendPacket.accept(rejPacket);
										
										state = RBTPConnectionState.TIMED_WAIT;
										
										if(synFinlastPacket != null)
											synFinlastPacket.destroy();
										
										synFinlastPacket = rejPacket;
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
									sendPacket.accept(synFinlastPacket);
								} else if(packet.ack()) {
									System.out.println("CONNECTION (IST): Server accepted challenge, connection established!");
									state = RBTPConnectionState.ESTABLISHED;
									
									outputStreamThread.init(packet.receiveWindow() << packet.scale(), synFinlastPacket.sequenceNumber());
									
									if(synFinlastPacket != null)
										synFinlastPacket.destroy();
									synFinlastPacket = null;
								} else if(packet.rej()) {
									System.out.println("CONNECTION (IST): Server declined challenge, connection rejected!");
									state = RBTPConnectionState.CLOSED;
									if(synFinlastPacket != null)
										synFinlastPacket.destroy();
									synFinlastPacket = null;
								} else {
									System.out.println("CONNECTION (IST): Received invalid packet, expected ACK/REJ. Closing...");
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case ESTABLISHED:
								if(packet.cha() && packet.ack()) {
									System.out.println("CONNECTION (IST): RE-received ACK-CHA, resending ACK.");
									sendPacket.accept(synFinlastPacket);
									
									packet.destroy();
								} else if(packet.ack()) {
									if(synFinlastPacket != null) {
										synFinlastPacket.destroy();
										synFinlastPacket = null;
									}
									
									System.out.println("CONNECTION (IST): Received ACK packet. seq: " + packet.sequenceNumber());
									outputStreamThread.acceptAck(packet);
								} else if(packet.fin()) {
									// TODO: handle close
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
										
										if(prevTime == -1) {
											prevTime = System.currentTimeMillis();
										}
									}
									else {
										System.out.println("CONNECTION (IST): Received duplicate packet!");
										packet.destroy();
									}
								}
								
								break;
							case TIMED_WAIT:
								timedWaitCount = 0;
								System.out.println("CONNECTION (IST): Re-received ACK-CHA, resending REJ.");
								sendPacket.accept(synFinlastPacket);
								
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
