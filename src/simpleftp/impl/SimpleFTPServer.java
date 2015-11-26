package simpleftp.impl;

import java.io.FileOutputStream;
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
 * Implementation of a SimpleFTP server,
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
		serverSocket.close();
	}
	
	/**
	 * Listens for connections.
	 *
	 * @throws IOException if one is encountered =.
	 */
	public void listen() throws IOException {
		serverSocket.listen();
		
		while(listen) {
			System.out.println("Waiting for new connection");
			RBTPSocket clientSocket = serverSocket.accept();
			
			Thread clientThread = new Thread(new ClientHandler(clientSocket));
			clientThread.start();
		}
		
		serverSocket.close();
	}
	
	/**
	 * Manages a client connection.
	 *
	 * @author Evan Bailey
	 */
	private class ClientHandler implements Runnable {
		boolean isMidPUT = false;
		String putFilename; // Must keep track of this for 2nd half of PUT

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

		private byte[] handlePut(byte content[]) {
			byte response[];
			Path path;
			String filename, errorMessage, message;

			System.out.println("Handling PUT request...");

			try {
				filename = new String(content, "UTF-8");
				path = Paths.get(filename);

				// If the file exists locally, respond with error
				if (Files.exists(path)) {
					errorMessage = "File exists on server, overwrite not allowed";
					response = SimpleFTP.buildMessage(SimpleFTP.ERR, errorMessage.getBytes("UTF-8"));
				}
				// Else continue with the PUT request
				else {
					message = "";
					response = SimpleFTP.buildMessage(SimpleFTP.RSP, message.getBytes("UTF-8"));

					// Keep track of filename for second part of PUT
					putFilename = filename;

					isMidPUT = true;
				}
			}
			catch(UnsupportedEncodingException ueex) {
				// Never reached
				errorMessage = "Server does not support UTF-8 encoding";
				response = SimpleFTP.buildMessage(SimpleFTP.ERR, errorMessage.getBytes());
			}

			return response;
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
		
		/**
		 * Handle the client connection
		 */
		@Override
		public void run() {
			ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
			
			while(listen && !clientSocket.isClosed()) {
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
						if(!isMidPUT) {
							ByteBuffer response = ByteBuffer.wrap(handlePut(content));
							while (response.hasRemaining())
								clientSocket.write(response);
						}
						else {
							// We do not send anything back to client
							FileOutputStream fouts = new FileOutputStream(putFilename);

							// Write bytes
							fouts.write(content);
							fouts.close();

							System.out.println("Received file " + putFilename + " from client.");

							isMidPUT = false;
						}
					} else if(SimpleFTP.FIN == opcode) {
						System.out.println("User closed connection.");
						clientSocket.close();
						break;
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
