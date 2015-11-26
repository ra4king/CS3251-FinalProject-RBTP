package edu.rbtp.impl;

import java.util.function.Consumer;

/**
 * A BindingInterface defines how a Bindable and the connection communicate.
 *
 * @author Roi Atalla
 */
public interface BindingInterface {
	short getPort();
	
	Consumer<RBTPPacket> getPacketSendConsumer();
	
	Consumer<RBTPPacket> getPacketReceivedConsumer();
	
	void setPacketReceivedConsumer(Consumer<RBTPPacket> packetRcvd);
	
	void unbind();
}
