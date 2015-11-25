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
	private int windowSize = 100000;
	private final long TIMEOUT = 100;
	
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
		synPacket.address = remoteAddress;
		synPacket.sourcePort(bindingInterface.getPort());
		synPacket.destinationPort(address.getPort());
		synPacket.sequenceNumber(rng.nextInt());
		synPacket.syn(true);
		setWindowSize(synPacket);
		
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
		outputStreamThread.init(synPacket.receiveWindow() << synPacket.scale());
		
		remoteAddress = synPacket.address;
		
		RBTPPacket chaPacket = new RBTPPacket();
		chaPacket.address = remoteAddress;
		chaPacket.sourcePort(bindingInterface.getPort());
		chaPacket.destinationPort(synPacket.address.getPort());
		chaPacket.sequenceNumber(rng.nextInt());
		chaPacket.syn(true);
		chaPacket.cha(true);
		setWindowSize(synPacket);
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
	
	private void setWindowSize(RBTPPacket packet) {
		int receiveWindow = this.windowSize;
		byte scale = 0;
		
		while(receiveWindow > 0xFFFF) {
			receiveWindow >>>= 1;
			scale++;
		}
		
		packet.scale(scale);
		packet.receiveWindow((short)receiveWindow);
	}
	
	public long read(ByteBuffer buffer, int offset, int length, boolean block) {
		return 0;
	}
	
	public long send(ByteBuffer buffer, int offset, int length, boolean block) {
		return 0;
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
		
		private int receiveWindow;
		
		RBTPOutputStreamThread() {
			lastSent = new ArrayList<>();
			ackPackets = new LinkedBlockingQueue<>();
		}
		
		public void init(int receiveWindow) {
			this.receiveWindow = receiveWindow;
		}
		
		public void acceptAck(RBTPPacket packet) {
			ackPackets.offer(packet);
		}
		
		@Override
		public void run() {
			int timeoutCount = 0;
			
			while(true) {
				if(state == RBTPConnectionState.CLOSED) {
					System.out.println("CONNECTION: Closed, OutputStreamThread exiting.");
					break;
				}
				
				try {
					RBTPPacket packet = ackPackets.poll(TIMEOUT, TimeUnit.MILLISECONDS);
					if(packet == null) {
						if(lastSent.size() > 0) {
							timeoutCount++;
							if(timeoutCount >= 10) {
								System.out.println("CONNECTION: Consecutive timeout count limit reached. Closing...");
								state = RBTPConnectionState.CLOSED;
								continue;
							}
							
							System.out.println("CONNECTION: Timeout! Resending " + lastSent.size() + " packets.");
							lastSent.forEach(sendPacket); // resend all
						}
					}
					else {
						timeoutCount = 0;
						
						// TODO: handle ACK
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
		
		RBTPInputStreamThread() {
			packetsQueue = new LinkedBlockingQueue<>();
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
				
				boolean success = true;
				
				byte[] bytes = digest.digest();
				for(int i = 0; i < n; i++) {
					if((bytes[i / 8] & (0x80 >>> (i%8))) != 0) {
						success = false;
						break;
					}
				}
				
				if(success || print) {
					System.out.println("\nHeader:");
					for(int i = 0; i < header.capacity(); i++) {
						System.out.println(header.get(i));
					}
					System.out.println();
				}
				
				return success;
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
			
			System.out.println("CONNECTION: Received SYN-CHA response! RandNum: " + randNumber);
			
			RBTPPacket challengeResponse = new RBTPPacket();
			challengeResponse.address = remoteAddress;
			challengeResponse.sourcePort(bindingInterface.getPort());
			challengeResponse.destinationPort(remoteAddress.getPort());
			challengeResponse.sequenceNumber((int)(synFinlastPacket.sequenceNumber() + 1));
			challengeResponse.cha(true);
			challengeResponse.ack(true);
			setWindowSize(challengeResponse);
			
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
			System.out.println("CONNECTION: Calculate challenge took " + (System.nanoTime() - time) / 1000000 + " ms. New RandNum: " + randNumber);
			
			return challengeResponse;
		}
		
		@Override
		public void run() {
			int timedWaitCount = 0;
			
			while(true) {
				try {
					if(state == RBTPConnectionState.CLOSED) { // TODO: not the right way to handle close, need to wait for all data to finish coming in
						System.out.println("CONNECTION: closed, InputStreamThread exiting.");
						break;
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
								System.out.println("CONNECTION: Timeout! Resending last init/fin packet");
								sendPacket.accept(synFinlastPacket);
							}
							else {
								System.out.println("CONNECTION: Init Fin Packet null!");
							}
						}
					}
					else {
						System.out.println("CONNECTION: Received packet (seq: " + packet.sequenceNumber() + ")!");
						
						switch(state) {
							case SYN_SENT:
								if(packet.cha() && packet.syn()) {
									if(synFinlastPacket != null)
										synFinlastPacket.destroy();
									sendPacket.accept(synFinlastPacket = calculateChallenge(packet));
									state = RBTPConnectionState.ACK_CHA_SENT;
								} else {
									System.out.println("CONNECTION: Received invalid packet, expected SYN-CHA. Closing...");
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case SYN_RCVD:
								if(packet.syn()) {
									System.out.println("CONNECTION: Re-received SYN, resending SYN-CHA.");
									sendPacket.accept(synFinlastPacket);
								} else if(packet.ack() && packet.cha()) {
									System.out.println("CONNECTION: Received ACK-CHA.");
									if(sha1BeginsWithNZeroes(packet, packet.metadata().get(7), true)) {
										System.out.println("Connection: client passed challenge, connection established!");
										
										RBTPPacket ackPacket = new RBTPPacket();
										ackPacket.address = remoteAddress;
										ackPacket.sourcePort(bindingInterface.getPort());
										ackPacket.destinationPort(remoteAddress.getPort());
										ackPacket.sequenceNumber((int)(synFinlastPacket.sequenceNumber() + 1));
										ackPacket.ack(true);
										setWindowSize(ackPacket);
										sendPacket.accept(ackPacket);
										
										state = RBTPConnectionState.ESTABLISHED;
										
										if(synFinlastPacket != null)
											synFinlastPacket.destroy();
										
										synFinlastPacket = ackPacket;
									} else {
										System.out.println("CONNECTION: client failed challenge, connection rejected!");
										RBTPPacket rejPacket = new RBTPPacket();
										rejPacket.address = remoteAddress;
										rejPacket.sourcePort(bindingInterface.getPort());
										rejPacket.destinationPort(remoteAddress.getPort());
										rejPacket.sequenceNumber((int)(synFinlastPacket.sequenceNumber() + 1));
										rejPacket.rej(true);
										setWindowSize(rejPacket);
										sendPacket.accept(rejPacket);
										
										state = RBTPConnectionState.TIMED_WAIT;
										
										if(synFinlastPacket != null)
											synFinlastPacket.destroy();
										
										synFinlastPacket = rejPacket;
									}
								} else {
									System.out.println("CONNECTION: Received invalid packet, expected ACK-CHA. Closing...");
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case ACK_CHA_SENT:
								if(packet.syn() && packet.cha()) {
									System.out.println("CONNECTION: Re-received SYN-CHA, resending ACK-CHA.");
									sendPacket.accept(synFinlastPacket);
								} else if(packet.ack()) {
									System.out.println("CONNECTION: Server accepted challenge, connection established!");
									state = RBTPConnectionState.ESTABLISHED;
									if(synFinlastPacket != null)
										synFinlastPacket.destroy();
									synFinlastPacket = null;
								} else if(packet.rej()) {
									System.out.println("CONNECTION: Server declined challenge, connection rejected!");
									state = RBTPConnectionState.CLOSED;
									if(synFinlastPacket != null)
										synFinlastPacket.destroy();
									synFinlastPacket = null;
								} else {
									System.out.println("CONNECTION: Received invalid packet, expected ACK/REJ. Closing...");
									state = RBTPConnectionState.CLOSED;
								}
								
								packet.destroy();
								
								break;
							case ESTABLISHED:
								if(packet.cha() && packet.ack()) {
									System.out.println("CONNECTION: RE-received ACK-CHA, resending ACK.");
									sendPacket.accept(synFinlastPacket);
									
									packet.destroy();
								} else if(packet.ack()) {
									if(synFinlastPacket != null) {
										synFinlastPacket.destroy();
										synFinlastPacket = null;
									}
									
									outputStreamThread.acceptAck(packet);
								}
								
								break;
							case TIMED_WAIT:
								timedWaitCount = 0;
								System.out.println("CONNECTION: Re-received ACK-CHA, resending REJ.");
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
