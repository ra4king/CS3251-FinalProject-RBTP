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
			if(closed && clients.containsKey(packet.address))
				return;
			
			BindingInterface clientBindingInterface = clients.get(packet.address);
			
			if(serverBindingInterface == null) {
				RBTPConnection newConnection = new RBTPConnection();
				BindingInterface newBindingInterface = new BindingInterface() {
					private Consumer<RBTPPacket> packetReceivedConsumer;
					
					@Override
					public int getPort() {
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
				
				if(acceptHandler != null)
					acceptHandler.accept(newConnection);
			} else if(clientBindingInterface.getPacketReceivedConsumer() != null) {
				clientBindingInterface.getPacketReceivedConsumer().accept(packet);
			}
		}
	}
}
