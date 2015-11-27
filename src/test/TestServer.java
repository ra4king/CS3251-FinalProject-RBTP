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
			
			clientSocket.getConnection().setWindowSize(1000000);
			
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
			
			buffer.limit(4);
			
			do {
				int read = socket.read(buffer);
				System.out.println("TEST: Read " + read + " bytes.");
			} while(buffer.position() < 4);
			
			buffer.flip();
			final int total = buffer.getInt();
			
			boolean allMatch = true;
			
			long time = -1;
			
			int count = 0;
			while(count < total) {
				buffer.clear();
				int read = socket.read(buffer);
				buffer.flip();
				
				if(time == -1) {
					time = System.nanoTime();
				}
				
				System.out.printf("TEST: Read %d bytes. Read so far: %d bytes.\n", read, count * 4);
				
				while(buffer.remaining() >= 4) {
					int nextCount = count++, nextBuf = buffer.getInt();
					if(nextCount != nextBuf) {
						System.out.printf("TEST: DID NOT MATCH! Count: %d, Buf: %d\n", nextCount, nextBuf);
						allMatch = false;
					}
				}
			}
			
			System.out.printf("TEST: Reading %d bytes took %.2f ms\n", total * 4, (System.nanoTime() - time) / 1000000.0);
			
			if(allMatch) {
				System.out.println("TEST: All match!");
			}
			
			buffer.clear();
			
			count = 0;
			while(count < total) {
				while(buffer.remaining() >= 4) {
					buffer.putInt(count++);
				}
				
				buffer.flip();
				int written = socket.write(buffer);
				System.out.printf("TEST: Wrote %d bytes. Written total %d bytes.\n", written, count * 4);
				buffer.compact();
			}
			
			System.out.printf("TEST: Written %d total bytes.\n", total * 4);
			
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
