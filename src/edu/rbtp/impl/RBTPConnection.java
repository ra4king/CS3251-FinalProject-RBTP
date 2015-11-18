package edu.rbtp.impl;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @author Roi Atalla
 */
public class RBTPConnection {
	private int port;
	private boolean closed;
	
	RBTPConnection(int port) {
		this.port = port;
	}
	
	Consumer<RBTPPacket> initialize(Consumer<RBTPPacket> sendPacket) {
		RBTPInputStreamThread rbtpIS = new RBTPInputStreamThread();
		Thread ist = new Thread();
		ist.setName("RBTP Input Stream Thread port: " + port);
		ist.start();
		
		Thread ost = new Thread(new RBTPOutputStreamThread(sendPacket));
		ost.setName("RBTP Output Stream Thread port: " + port);
		ost.start();
		
		return rbtpIS;
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
	
	class RBTPInputStreamThread implements Runnable, Consumer<RBTPPacket> {
		private LinkedBlockingQueue<RBTPPacket> packetsQueue;
		
		RBTPInputStreamThread() {
			packetsQueue = new LinkedBlockingQueue<>();
		}
		
		@Override
		public void accept(RBTPPacket packet) {
			packetsQueue.offer(packet);
		}
		
		final long TIMEOUT = 100;
		
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
