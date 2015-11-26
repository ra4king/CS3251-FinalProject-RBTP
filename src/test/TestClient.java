package test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Random;

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
			
			System.out.println("TEST: Connected!");
			
			ByteBuffer buffer = ByteBuffer.allocate(1000);
			
			Random rng = new Random(seed);
			
			buffer.putInt(seed);
			buffer.flip();
			socket.write(buffer);
			buffer.clear();
			
			long bytesSent = 0;
			
			int count = 1000;
			while(count > 0) {
				while(buffer.remaining() >= 4) {
					buffer.putInt(rng.nextInt());
				}
				
				buffer.flip();
				int written = socket.write(buffer);
				bytesSent += written;
				count -= written;
				System.out.println("TEST: Wrote " + written + " bytes. bytes left: " + count);
				buffer.compact();
			}
			
			System.out.println("TEST: Written " + bytesSent + " total bytes.");
			
			rng = new Random(seed * 2);
			buffer.clear();
			
			count = 1000;
			while(count > 0) {
				int read = socket.read(buffer);
				buffer.flip();
				
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
			
			//socket.close();
			
			while(!socket.isClosed()) {
				Thread.sleep(10);
			}
		} catch(Exception exc) {
			exc.printStackTrace();
		}
	}
}
