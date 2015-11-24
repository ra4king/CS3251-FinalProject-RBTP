package edu.rbtp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * @author Roi Atalla
 */
public class RBTPSocketAddress {
	private InetSocketAddress address;
	private short port;
	
	/**
	 * Creates an RBTP Socket Address using the combination of a network address and the RBTP port.
	 * @param address Socket Address, which is the address and UDP port of the remote machine running the NetworkManager
	 * @param port the RBTP port
	 */
	public RBTPSocketAddress(InetSocketAddress address, int port) {
		this.setAddress(address);
		this.setPort(port);
	}
	
	public SocketAddress getAddress() {
		return address;
	}
	
	public void setAddress(InetSocketAddress address) {
		this.address = address;
	}
	
	public short getPort() {
		return port;
	}
	
	public void setPort(int port) {
		this.port = (short)port;
	}
	
	@Override
	public boolean equals(Object other) {
		if(!(other instanceof RBTPSocketAddress))
			return false;
		
		RBTPSocketAddress otherAddress= (RBTPSocketAddress)other;
		return this.address.equals(otherAddress.address) && this.port == otherAddress.port;
	}
	
	@Override
	public int hashCode() {
		return address.hashCode() + port;
	}
	
	@Override
	public String toString() {
		return address + ":" + ((int)port & 0xFFFF);
	}
}
