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
		try {
			System.out.println("TEST: New connection!");
			
			ByteBuffer buffer = ByteBuffer.allocate(1000);
			
			Random rng = null;
			
			long time = System.currentTimeMillis();
			
			int seed = 0;
			
			int count = 1000;
			while(count > 0) {
				int read = socket.read(buffer);
				buffer.flip();
				
				if(rng == null && buffer.remaining() >= 4) {
					rng = new Random(seed = buffer.getInt());
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
				
				if(match) {
					System.out.println("TEST: ALL MATCH SO FAR! Remaining to read: " + count);
				}
				
				buffer.compact();
			}
			
			long diffTime = System.currentTimeMillis() - time;
			
			System.out.printf("TEST: Total bytes read: 1000, in %.3f seconds\n", (diffTime / 1000.0));
			
			buffer.clear();
			
			long bytesSent = 0;
			
			rng = new Random(seed * 2);
			
			count = 1000;
			while(count > 0) {
				while(buffer.remaining() >= 4) {
					buffer.putInt(rng.nextInt());
				}
				
				buffer.flip();
				int sent = socket.write(buffer);
				bytesSent += sent;
				count -= sent;
				System.out.println("TEST: Wrote " + sent + " bytes. bytes left to send: " + count);
				buffer.compact();
			}
			
			System.out.println("TEST: Written " + bytesSent + " total bytes.");
			
			socket.close();
			
			while(!socket.isClosed()) {
				Thread.sleep(100);
			}
			
			System.out.println("Closed.");
		}
		catch(Exception exc) {
			exc.printStackTrace();
		}
	}
}
