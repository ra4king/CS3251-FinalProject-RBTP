package test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import edu.rbtp.RBTPServerSocket;
import edu.rbtp.RBTPSocket;
import edu.rbtp.impl.NetworkManager;

/**
 * @author Roi Atalla
 */
public class TestServer implements Runnable {
	public static void main(String[] args) throws IOException {
		NetworkManager.init(61);
		
		RBTPServerSocket serverSocket = new RBTPServerSocket();
		serverSocket.bind(1000);
		serverSocket.listen();
		
		System.out.println("Server listening on port 1000, waiting...");
		
		while(true) {
			RBTPSocket clientSocket = serverSocket.accept();
			if(clientSocket == null) {
				break;
			}
			
			new Thread(new TestServer(clientSocket)).start();
		}
	}
	
	private RBTPSocket socket;
	
	public TestServer(RBTPSocket socket) {
		this.socket = socket;
	}
	
	@Override
	public void run() {
		System.out.println("TEST: New connection!");
		
		ByteBuffer buffer = ByteBuffer.allocate(1000);
		
		Random rng = null;
		
		long time = System.currentTimeMillis();
		
		int count = 1000;
		while(count > 0) {
			int read = socket.read(buffer);
			buffer.flip();
			
			if(rng == null && buffer.remaining() >= 4) {
				rng = new Random(buffer.getInt());
				read -= 4;
			}
			
			count -= read;
			
			System.out.println("TEST: Read " + read + " bytes.");
			
			boolean match = true;
			
			while(buffer.remaining() >= 4) {
				if(rng.nextInt() != buffer.getInt()) {
					System.out.println("TEST: DID NOT MATCH!");
					match = false;
					break;
				}
			}
			
			if(match)
				System.out.println("TEST: ALL MATCH SO FAR! Remaining to read: " + count);
			
			buffer.compact();
		}
		
		long diffTime = System.currentTimeMillis() - time;
		
		System.out.printf("TEST: Total bytes read: 1000000, in %.3f seconds\n", (diffTime / 1000.0));
		
		socket.close();
		
		while(!socket.isClosed()) {
			try { Thread.sleep(100); } catch(Exception exc) {}
		}
	}
}
