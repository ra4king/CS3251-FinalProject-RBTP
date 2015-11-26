package simpleftp.impl;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import edu.rbtp.RBTPServerSocket;
import edu.rbtp.RBTPSocket;
import simpleftp.SimpleFTP;

/**
 * TODO Documentation
 *
 * @author Evan Bailey
 */
public class SimpleFTPServer {
	private final RBTPServerSocket serverSocket;
	private ArrayList<ClientHandler> clients;
	
	private boolean listen = true;
	
	/**
	 * Constructor for SFTPServer.
	 */
	public SimpleFTPServer() throws IOException {
		serverSocket = new RBTPServerSocket();
		serverSocket.bind(1000);
		
		clients = new ArrayList<>();
	}
	
	/**
	 * Requests that RBTP use the provided window size.
	 *
	 * @param windowSize - proposed window size
	 */
	public synchronized void setWindowSize(int windowSize) {
		for(ClientHandler client : clients) {
			client.clientSocket.getConnection().setWindowSize(windowSize);
		}
	}
	
	/**
	 * Alerts the server to stop accepting new connections.
	 * <p>
	 * Note that any existing connections will stay alive until they close themselves.
	 */
	public synchronized void close() {
		listen = false;
	}
	
	/**
	 * Listens for connections.
	 *
	 * @throws IOException if one is encountered =.
	 */
	public void listen() throws IOException {
		RBTPSocket clientSocket;
		Thread clientThread;
		
		serverSocket.listen();
		
		while(listen) {
			clientSocket = serverSocket.accept();
			
			clientThread = new Thread(new ClientHandler(clientSocket));
			clientThread.setDaemon(false); // Any existing connections will finish before exiting
			clientThread.start();
		}
		
		serverSocket.close();
	}
	
	/**
	 * TODO: Documentation
	 * <p>
	 * TODO: Switch to RBTP sockets
	 * <p>
	 * TODO: If a file not found is requested first, IOException is throws for following gets
	 *
	 * @author Evan Bailey
	 */
	private class ClientHandler implements Runnable {
		RBTPSocket clientSocket;
		
		/**
		 * Constructor for ClientHandler.
		 *
		 * @param clientSocket - connection to client
		 * @throws IOException if one is encountered.
		 */
		public ClientHandler(RBTPSocket clientSocket) throws IOException {
			this.clientSocket = clientSocket;
			System.out.println("Accepted client connection");
		}
		
		private byte[] handleGet(byte content[]) {
			byte response[];
			String errorMessage, filename;
			Path path;
			
			try {
				filename = new String(content, "UTF-8");
				
				System.out.println("Received content length: " + content.length + ", filename: " + filename);
				
				path = Paths.get(filename);
				if(Files.exists(path)) {
					response = SimpleFTP.buildMessage(SimpleFTP.RSP, Files.readAllBytes(path));
				} else {
					// TODO for some reason, error message not being sent?
					errorMessage = "File not found";
					response = SimpleFTP.buildMessage(SimpleFTP.ERR, errorMessage.getBytes("UTF-8"));
				}
			}
			catch(UnsupportedEncodingException ueex) {
				// Never reached
				errorMessage = "Server does not support UTF-8 encoding";
				response = SimpleFTP.buildMessage(SimpleFTP.ERR, errorMessage.getBytes());
			}
			catch(IOException ioex) {
				// Never reached due to checking that file exists
				errorMessage = "IOException encountered while reading file";
				response = SimpleFTP.buildMessage(SimpleFTP.ERR, errorMessage.getBytes());
			}
			
			System.out.println("Handle get finished, returning response. opcode: " + response[0]);
			
			return response;
		}
		
		private boolean handlePut(byte content[]) {
			// TODO: Implement
			// Put request received
			// If file exists locally, respond err
			// Else respond RSP
			// Next message should be PUT
			// If not, just err and return
			// If put, get file and save locally knowing file doesnt exist
			return false;
		}
		
		/**
		 * Handle the client connection
		 */
		@Override
		public void run() {
			ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
			
			while(listen) {
				try {
					buffer.clear();
					do {
						clientSocket.read(buffer);
					} while(buffer.position() < 4);
					buffer.flip();
					
					int size = buffer.getInt();
					buffer.compact();
					
					while(size > buffer.position()) {
						clientSocket.read(buffer);
					}
					
					buffer.flip();
					
					byte opcode = buffer.get();
					
					System.out.println("Received opcode: " + opcode);
					
					byte[] content = new byte[buffer.remaining()];
					buffer.get(content);
					
					if(SimpleFTP.GET == opcode) {
						ByteBuffer response = ByteBuffer.wrap(handleGet(content));
						while(response.hasRemaining())
							clientSocket.write(response);
					} else if(SimpleFTP.PUT == opcode) {
						// TODO Implement (extra credit)
					}
				}
				catch(IOException ioex) {
					System.out.println("Lost connection to client while receiving message");
					break;
				}
			}
			
			// Close socket when done
			try {
				clientSocket.close();
			}
			catch(Exception exc) {
			}
			
			clients.remove(this);
		}
	}
}
