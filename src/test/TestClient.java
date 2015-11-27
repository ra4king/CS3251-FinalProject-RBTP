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
			new Thread(new TestClient(1234 * i)).start();
		}
	}
	
	private int seed;
	
	public TestClient(int seed) {
		this.seed = seed;
	}
	
	@Override
	public void run() {
		try {
			RBTPSocket socket = new RBTPSocket();
			
			System.out.println("TEST: Connecting...");
			
			socket.connect(new RBTPSocketAddress(new InetSocketAddress("localhost", 5000), 1000));
			socket.getConnection().setWindowSize(10000);
			
			System.out.println("TEST: Connected!");
			
			ByteBuffer buffer = ByteBuffer.allocate(1000);

//			Random rng = new Random(seed);
			
			final int total = 100000;
			
			buffer.putInt(seed);
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
				System.out.println("TEST: Wrote " + written + " bytes. bytes left: " + count);
				buffer.compact();
			}
			
			System.out.println("TEST: Written " + total + " total bytes.");

//			rng = new Random(seed * 2);
			
			buffer.clear();
			
			boolean allMatch = true;
			
			count = 0;
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
			
			//socket.close();
			
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
