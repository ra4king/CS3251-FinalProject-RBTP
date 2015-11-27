package test;

import java.io.IOException;
import java.nio.ByteBuffer;

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
			
			clientSocket.getConnection().setWindowSize(10000);
			
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
			
			long time = System.currentTimeMillis();
			
			buffer.limit(8);
			
			do {
				int read = socket.read(buffer);
				System.out.println("TEST: Read " + read + " bytes.");
			} while(buffer.position() < 8);
			
			buffer.flip();
			final int seed = buffer.getInt();
			final int total = buffer.getInt();
			
			//Random rng = new Random(seed = buffer.getInt());
			
			boolean allMatch = true;
			
			int count = 0;
			while(count < total) {
				buffer.clear();
				int read = socket.read(buffer);
				buffer.flip();
				
				System.out.println("TEST: Read " + read + " bytes.");
				
				while(buffer.remaining() >= 4) {
					int nextRng = count++, nextBuf = buffer.getInt();
					if(nextRng != nextBuf) {
						System.out.println("TEST: DID NOT MATCH! Count: " + nextRng + ", Buf: " + nextBuf);
						allMatch = false;
					}
				}
			}
			
			if(allMatch) {
				System.out.println("TEST: All match!");
			}
			
			long diffTime = System.currentTimeMillis() - time;
			
			System.out.printf("TEST: Total bytes read: 1000, in %.3f seconds\n", (diffTime / 1000.0));
			
			buffer.clear();

//			rng = new Random(seed * 2);
			
			count = 0;
			while(count < total) {
				while(buffer.remaining() >= 4) {
					buffer.putInt(count++);
				}
				
				buffer.flip();
				int sent = socket.write(buffer);
				System.out.println("TEST: Wrote " + sent + " bytes. bytes left to send: " + count);
				buffer.compact();
			}
			
			System.out.println("TEST: Written " + total + " total bytes.");
			
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
