package edu.rbtp.impl;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author Roi Atalla
 */
public class RBTPConnection {
	private int port;
	
	RBTPConnection(int port, Supplier<RBTPPacket> packetReceived, Consumer<RBTPPacket> sendPacket) {
		this.port = port;
		
		Thread ist = new Thread(new RBTPInputStreamThread(packetReceived));
		ist.setName("RBTP Input Stream Thread port: " + port);
		ist.start();
		
		Thread ost = new Thread(new RBTPOutputStreamThread(sendPacket));
		ost.setName("RBTP Output Stream Thread port: " + port);
		ost.start();
	}
	
	private class RBTPOutputStreamThread implements Runnable {
		private Consumer<RBTPPacket> sendPacket;
		
		RBTPOutputStreamThread(Consumer<RBTPPacket> sendPacket) {
			this.sendPacket = sendPacket;
		}
		
		@Override
		public void run() {
			//TODO: packetize output stream and send them
		}
	}
	
	private class RBTPInputStreamThread implements Runnable {
		private Supplier<RBTPPacket> packetReceived;
		
		RBTPInputStreamThread(Supplier<RBTPPacket> packetReceived) {
			this.packetReceived = packetReceived;
		}
		
		@Override
		public void run() {
			//TODO: process incoming packet queue
		}
	}
}
