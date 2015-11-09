package edu.rbtp;

/**
 * @author Roi Atalla
 */
public class RBTPSocketAddress {
	private String address;
	private int port;
	
	public RBTPSocketAddress(String address, int port) {
		this.setAddress(address);
		this.setPort(port);
	}
	
	public String getAddress() {
		return address;
	}
	
	public void setAddress(String address) {
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
