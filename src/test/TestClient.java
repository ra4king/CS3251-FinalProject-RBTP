package test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import edu.rbtp.RBTPSocket;
import edu.rbtp.RBTPSocketAddress;
import edu.rbtp.impl.NetworkManager;

/**
 * @author Roi Atalla
 */
public class TestClient implements Runnable {
	public static void main(String[] args) throws Exception {
		NetworkManager.init(60);
		
		for(int i = 0; i < 1; i++) {
			new Thread(new TestClient()).start();
		}
	}
	
	@Override
	public void run() {
		try {
			RBTPSocket socket = new RBTPSocket();
			
			System.out.println("TEST: Connecting...");
			
			socket.connect(new RBTPSocketAddress(new InetSocketAddress("localhost", 61), 1000));
			socket.getConnection().setWindowSize(1000000);
			
			System.out.println("TEST: Connected!");
			
			ByteBuffer buffer = ByteBuffer.allocate(1000);
			
			final int total = 1000000;
			
			buffer.putInt(total);
			buffer.flip();
			socket.write(buffer);
			buffer.clear();
			
			int count = 0;
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

			buffer.clear();
			
			boolean allMatch = true;
			
			long time = -1;
			
			count = 0;
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
			
			socket.close();
			
			while(!socket.isClosed()) {
				Thread.sleep(10);
			}
			
			System.out.println("Closed.");
		}
		catch(Exception exc) {
			exc.printStackTrace();
		}
	}
}
