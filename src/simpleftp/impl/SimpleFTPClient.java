package simpleftp.impl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import edu.rbtp.RBTPSocket;
import edu.rbtp.RBTPSocketAddress;
import simpleftp.SimpleFTP;

/**
 * TODO Documentation
 * TODO ex: SFTP client commands (disconnect, get, put, etc)
 * <p>
 *
 * @author Evan
 */
public class SimpleFTPClient {
	
	private final int port;
	private final String netEmuIP;
	private final int netEmuPort;
	
	// TODO - Switch to RBTP Socket
	private final RBTPSocket socket;
	
	/**
	 * Constructor for SFTPClient.
	 * <p></p>
	 * @param port       - Port on which SFTPClient is bound
	 * @param netEmuIP   - IP address NetEmu is running on
	 * @param netEmuPort - Port NetEmu is bound to
	 */
	public SimpleFTPClient(int port, String netEmuIP, int netEmuPort) throws IOException {
		this.port = port;
		this.netEmuIP = netEmuIP;
		this.netEmuPort = netEmuPort;
		
		socket = new RBTPSocket();
		socket.connect(new RBTPSocketAddress(new InetSocketAddress(netEmuIP, netEmuPort), 1000));
	}
	
	/**
	 * Requests that RBTP use the provided window size.
	 *
	 * @param windowSize - proposed window size
	 */
	public synchronized void setWindowSize(int windowSize) {
		socket.getConnection().setWindowSize(windowSize);
	}
	
	/**
	 * Sends a file to the SFTP server via PUT.
	 *
	 * @param filename    - the file to PUT
	 * @param fileBytes	  - bytes of the file to PUT
	 * @returns	true if the file was PUT, else false.
	 * @throws FileNotFoundException if the file is not found at the server.
	 * @throws IOException if the connection is lost.
	 */
	public boolean put(String filename, byte fileBytes[]) throws IOException {
		byte putRequest[] = SimpleFTP.buildMessage(SimpleFTP.PUT, filename.getBytes("UTF-8"));

		// Request to PUT a file (content is filename)
		socket.write(ByteBuffer.wrap(putRequest));

		// First 4 bytes denotes length of remainder of message
		ByteBuffer buffer = ByteBuffer.allocate(4);
		do {
			socket.read(buffer);
		} while (buffer.position() < 4);
		buffer.flip();

		ByteBuffer content = ByteBuffer.allocate(buffer.getInt());

		// Read remainder of message
		do {
			socket.read(content);
		} while (content.hasRemaining());

		// Check response type
		byte contentBytes[] = content.array();

		if (SimpleFTP.RSP == contentBytes[0]) {
			// Prepare to send file bytes
			byte putPacket[] = SimpleFTP.buildMessage(SimpleFTP.PUT, fileBytes);

			// Send final PUT packet
			socket.write(ByteBuffer.wrap(putPacket));

			return true;
		}

		// Treat any OPCODE besides RSP as a PUT request rejection
		return false;
	}
	
	/**
	 * Fetches a file from the SFTP server via GET.
	 *
	 * @param filename - the file to GET
	 * @return the requested file, in bytes.
	 * @throws IOException if the connection is lost.
	 */
	public byte[] get(String filename) throws IOException {
		byte getRequest[] = SimpleFTP.buildMessage(SimpleFTP.GET, filename.getBytes("UTF-8"));
		
		socket.write(ByteBuffer.wrap(getRequest));

		// First 4 bytes denotes length of remainder of message
		ByteBuffer buffer = ByteBuffer.allocate(4);
		do {
			socket.read(buffer);
		} while(buffer.position() < 4);
		buffer.flip();
		
		ByteBuffer content = ByteBuffer.allocate(buffer.getInt());

		// Read remainder of message
		do {
			socket.read(content);
		} while(content.hasRemaining());
		
		System.out.println("Finished reading " + content.capacity() + " bytes");
		
		return content.array();
	}
}