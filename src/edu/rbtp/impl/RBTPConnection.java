package edu.rbtp.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import edu.rbtp.RBTPSocketAddress;

/**
 * @author Roi Atalla
 */
public class RBTPConnection implements Bindable {
	private enum RBTPConnectionState {
		CLOSED, LISTEN, SYN_RCVD, CHA_ACK_RCVD, SYN_SENT, CHA_RCVD, CHA_ACK_SENT, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2,
		CLOSING, TIMED_WAIT, CLOSE_WAIT, LAST_ACK
	}
	
	private volatile RBTPConnectionState state;
	private int windowSize = 100000;
	
	public RBTPConnection() {
		state = RBTPConnectionState.CLOSED;
	}
	
	RBTPConnection(boolean listen) {
		state = RBTPConnectionState.LISTEN;
	}
	
	private volatile boolean closed;
	private BindingInterface bindingInterface;
	private RBTPSocketAddress remoteAddress;
	
	public boolean isBound() {
		return bindingInterface != null;
	}
	
	public void bind(BindingInterface bindingInterface) {
		if(this.bindingInterface != null)
			throw new IllegalStateException("Already bound.");
		
		this.bindingInterface = bindingInterface;
		
		bindingInterface.setPacketReceivedConsumer(new RBTPInputStreamThread());
		
		Thread ist = new Thread();
		ist.setName("RBTP Input Stream Thread port: " + bindingInterface.getPort());
		ist.start();
		
		Thread ost = new Thread(new RBTPOutputStreamThread(bindingInterface.getPacketSendConsumer()));
		ost.setName("RBTP Output Stream Thread port: " + bindingInterface.getPort());
		ost.start();
	}
	
	public RBTPSocketAddress getRemoteAddress() {
		return remoteAddress;
	}
	
	private static Random rng = new Random();
	
	public void connect(RBTPSocketAddress address) throws IOException {
		if(remoteAddress != null)
			throw new IllegalStateException("Already connected.");
		
		if(bindingInterface == null)
			throw new IllegalStateException("Socket not bound.");
		
		this.remoteAddress = address;
		
		state = RBTPConnectionState.SYN_SENT;
		RBTPPacket packet = new RBTPPacket();
		packet.sourcePort(bindingInterface.getPort());
		packet.destinationPort(address.getPort());
		packet.sequenceNumber(rng.nextInt());
		packet.syn(true);
		setWindowSize(packet);
		
		bindingInterface.getPacketSendConsumer().accept(packet);
		
		int tryCount = 0;
		while(state != RBTPConnectionState.ESTABLISHED) {
			try {
				this.wait(1000000); // 1 second
			} catch(InterruptedException exc) {}
			
			tryCount++;
			if(tryCount == 10) {
				close();
				throw new IOException("Connect failed.");
			}
		}
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
	
	public boolean isClosed() {
		return closed;
	}
	
	public void close() {
		closed = true;
	}
	
	private class RBTPOutputStreamThread implements Runnable {
		private Consumer<RBTPPacket> sendPacket;
		
		RBTPOutputStreamThread(Consumer<RBTPPacket> sendPacket) {
			this.sendPacket = sendPacket;
		}
		
		@Override
		public void run() {
			// TODO: packetize output stream and send them
		}
	}
	
	private class RBTPInputStreamThread implements Runnable, Consumer<RBTPPacket> {
		private LinkedBlockingQueue<RBTPPacket> packetsQueue;
		
		RBTPInputStreamThread() {
			packetsQueue = new LinkedBlockingQueue<>();
		}
		
		@Override
		public void accept(RBTPPacket packet) {
			packetsQueue.offer(packet);
		}
		
		private final long TIMEOUT = 100;
		
		@Override
		public void run() {
			while(true) {
				try {
					RBTPPacket packet = packetsQueue.poll(TIMEOUT, TimeUnit.MILLISECONDS);
					if(packet == null) {
						// TODO: handle timeout!
					}
					else {
						// TODO: we have a packet
						
						packet.destroy();
					}
				} catch(Exception exc) {
					exc.printStackTrace();
				}
				
				if(closed)
					break;
			}
		}
	}
}
