package edu.rbtp.impl;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @author Roi Atalla
 */
public class RBTPConnection implements Bindable {
	private volatile boolean closed;
	private BindingInterface bindingInterface;
	
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
