package edu.rbtp.impl;

import java.util.HashMap;
import java.util.function.Consumer;

import edu.rbtp.RBTPSocketAddress;

/**
 * @author Roi Atalla
 */
public class RBTPServer implements Bindable {
	private BindingInterface serverBindingInterface;
	private Consumer<RBTPConnection> acceptHandler;
	private volatile boolean closed = false;
	
	public RBTPServer() {}
	
	public boolean isClosed() {
		return closed;
	}
	
	public void close() {
		closed = true;
	}
	
	@Override
	public boolean isBound() {
		return serverBindingInterface != null;
	}
	
	@Override
	public void bind(BindingInterface bindingInterface) {
		if(this.serverBindingInterface != null)
			throw new IllegalStateException("Already bound.");
		
		this.serverBindingInterface = bindingInterface;
		this.serverBindingInterface.setPacketReceivedConsumer(new PacketConsumer());
	}
	
	public void setAcceptHandler(Consumer<RBTPConnection> acceptHandler) {
		this.acceptHandler = acceptHandler;
	}
	
	private class PacketConsumer implements Consumer<RBTPPacket> {
		private HashMap<RBTPSocketAddress, BindingInterface> clients;
		
		PacketConsumer() {
			clients = new HashMap<>();
		}
		
		@Override
		public void accept(RBTPPacket packet) {
			if(acceptHandler == null || (closed && !clients.containsKey(packet.address))) {
				System.out.println("SERVER: NOPE");
				return;
			}
			
			System.out.println("SERVER: Received packet (seq: " + packet.sequenceNumber() + ") from " + packet.address);
			
			BindingInterface clientBindingInterface = clients.get(packet.address);
			
			if(clientBindingInterface == null) {
				System.out.println("SERVER: New connection from " + packet.address);
				
				RBTPConnection newConnection = new RBTPConnection();
				BindingInterface newBindingInterface = new BindingInterface() {
					private Consumer<RBTPPacket> packetReceivedConsumer;
					
					@Override
					public short getPort() {
						return serverBindingInterface.getPort();
					}
					
					@Override
					public Consumer<RBTPPacket> getPacketSendConsumer() {
						return serverBindingInterface.getPacketSendConsumer();
					}
					
					@Override
					public Consumer<RBTPPacket> getPacketReceivedConsumer() {
						return packetReceivedConsumer;
					}
					
					@Override
					public void setPacketReceivedConsumer(Consumer<RBTPPacket> packetRcvd) {
						this.packetReceivedConsumer = packetRcvd;
					}
					
					@Override
					public void unbind() {
						clients.remove(packet.address);
					}
				};
				clients.put(packet.address, newBindingInterface);
				newConnection.bind(newBindingInterface);
				newConnection.accept(packet);
				
				new Thread(() -> {
					while(!newConnection.isConnected() && !newConnection.isClosed()) {
						try {
							Thread.sleep(100);
						} catch(Exception exc) {}
						
						if(newConnection.isConnected())
							acceptHandler.accept(newConnection);
					}
				}).start();
			} else if(clientBindingInterface.getPacketReceivedConsumer() != null) {
				clientBindingInterface.getPacketReceivedConsumer().accept(packet);
			}
		}
	}
}
