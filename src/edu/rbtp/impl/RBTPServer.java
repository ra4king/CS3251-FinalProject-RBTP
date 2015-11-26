package edu.rbtp.impl;

import static edu.rbtp.tools.BufferPool.*;

import java.util.HashMap;
import java.util.function.Consumer;

import edu.rbtp.RBTPSocketAddress;

/**
 *
 * The Server implementation becomes the Bindable instead of the Connection. RBTPConnections then bind to this class
 * instead of the NetworkManager. This allows each server to handle its own multiplexing.
 * 
 * @author Roi Atalla
 */
public class RBTPServer implements Bindable {
	private BindingInterface serverBindingInterface;
	private Consumer<RBTPConnection> acceptHandler;
	private volatile boolean closed = false;
	
	public RBTPServer() {
	}
	
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
		if(this.serverBindingInterface != null) {
			throw new IllegalStateException("Already bound.");
		}
		
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
				if(PRINT_DEBUG) {
					System.out.println("SERVER: NOPE");
				}
				return;
			}
			
			if(PRINT_DEBUG) {
				System.out.println("SERVER: Received packet (seq: " + packet.sequenceNumber() + ") from " + packet.address);
			}
			
			// Check if this packet came from someone we know already
			BindingInterface clientBindingInterface = clients.get(packet.address);
			
			// If no binding exists, it's a spurious packet or a SYN from a new connection
			if(clientBindingInterface == null) {
				if(!packet.syn()) {
					if(PRINT_DEBUG) {
						System.out.println("SERVER: Received non-SYN initial packet?!");
					}
					return;
				}
				
				if(PRINT_DEBUG) {
					System.out.println("SERVER: New connection from " + packet.address);
				}
				
				RBTPConnection newConnection = new RBTPConnection();
				BindingInterface newBindingInterface = new BindingInterface() {
					private Consumer<RBTPPacket> packetReceivedConsumer;
					
					@Override
					public short getPort() {
						return serverBindingInterface.getPort();
					}
					
					/**
					 * the packet send consumer is the same as the one NetworkManager gave us.
					 * all connections share the same sender
					 */
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
						
						if(closed && clients.isEmpty()) {
							serverBindingInterface.unbind();
						}
					}
				};
				clients.put(packet.address, newBindingInterface);
				newConnection.bind(newBindingInterface);
				newConnection.accept(packet);
				
				// Start a small thread that waits until the connection is fully made until it sends the connection up
				// to the user for accept()
				// If a connection fails, it fails silently without the user even knowing one was attempted
				new Thread(() -> {
					while(!newConnection.isConnected() && !newConnection.isClosed()) {
						try {
							Thread.sleep(10);
						}
						catch(Exception exc) {
						}
					}
					
					if(newConnection.isConnected()) {
						acceptHandler.accept(newConnection);
					}
				}).start();
			} else if(clientBindingInterface.getPacketReceivedConsumer() != null) {
				clientBindingInterface.getPacketReceivedConsumer().accept(packet);
			}
		}
	}
}
