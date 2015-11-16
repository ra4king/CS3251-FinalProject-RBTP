package edu.rbtp;

import java.net.SocketAddress;

/**
 * @author Roi Atalla
 */
public class RBTPSocketAddress {
	private SocketAddress address;
	private int port;
	
	/**
	 * Creates an RBTP Socket Address using the combination of a network address and the RBTP port.
	 * @param address Socket Address, which is the address and port of the remote machine running the NetworkManager
	 * @param port the RBTP port
	 */
	public RBTPSocketAddress(SocketAddress address, int port) {
		this.setAddress(address);
		this.setPort(port);
	}
	
	public SocketAddress getAddress() {
		return address;
	}
	
	public void setAddress(SocketAddress address) {
		this.address = address;
	}
	
	public int getPort() {
		return port;
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	@Override
	public String toString() {
		return address + ":" + port;
	}
}
