package edu.rbtp.impl;

import java.util.function.Consumer;

/**
 * @author Roi Atalla
 */
public interface BindingInterface {
	int getPort();
	Consumer<RBTPPacket> getPacketSendConsumer();
	Consumer<RBTPPacket> getPacketReceivedConsumer();
	void setPacketReceivedConsumer(Consumer<RBTPPacket> packetRcvd);
	void unbind();
}