package simpleftp;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.util.Scanner;

import edu.rbtp.impl.NetworkManager;
import simpleftp.impl.SimpleFTPServer;

public class SimpleFTPServerLauncher {
	
	private static void changeWindowSize(SimpleFTPServer server, String windowSizeStr) {
		int windowSize;
		
		try {
			windowSize = Integer.parseInt(windowSizeStr);
			
			server.setWindowSize(windowSize);
		}
		catch(NumberFormatException nfex) {
			System.out.println("Incorrect parameters, window size must be integer.");
		}
	}
	
	/**
	 * Entry-point for execution.
	 * <p>
	 * To run:
	 * $ java SFTPServer X A P
	 * X: Even port number SFTPServer will bind to. Must be client port + 1.
	 * A: IP address of NetEmu
	 * P: UDP port number of NetEmu
	 *
	 * @param args - command-line arguments
	 */
	public static void main(String args[]) {
		int port, netEmuPort;
		String netEmuIP;
		SimpleFTPServer server = null;
		
		if(args.length != 3) {
			System.out.println("Incorrect parameters. Usage: $ java SFTPClient X A P");
			System.exit(0);
		}
		
		try {
			// Parse arguments, create client
			port = Integer.parseInt(args[0]);
			NetworkManager.init(port);
			
			netEmuIP = args[1]; // unused
			netEmuPort = Integer.parseInt(args[2]); // unused
			
			// Create server
			server = new SimpleFTPServer();
			
			System.out.println("Server established.");
			
			// Listen for input
			new Thread(new InputManager(server)).start();
			
			// Listen for connections
			server.listen();
			
		}
		catch(ConnectException cex) {
			System.out.println("ERROR: Connection failed.");
			System.exit(0);
		}
		catch(SocketException sex) {
			System.out.println("ERROR: Socket creation failed.");
			System.exit(0);
		}
		catch(IOException ioex) {
			System.out.println("ERROR: IOException encountered. Please restart and try again");
			System.exit(0);
		}
	}

	private static class InputManager implements Runnable {
		boolean run = true;
		Scanner scanner = new Scanner(System.in);
		SimpleFTPServer server;
		String input;
		String inputArray[];
		
		/**
		 * Constructor for InputManager.
		 *
		 * @param server - SimpleFTP server associated with this InputManager
		 */
		public InputManager(SimpleFTPServer server) {
			this.server = server;
		}
		
		@Override
		public void run() {
			// Listen for commands
			while(run) {
				System.out.print("> ");
				input = scanner.nextLine();
				
				/*
				 * TERMINATE COMMAND
				 */
				if(input.equalsIgnoreCase("terminate")) {
					System.out.println("Shutting down server.");
					server.close();
				}
				
				/*
				 * WINDOW COMMAND
				 */
				else if(input.toLowerCase().startsWith("window ")) {
					// Get filename argument
					inputArray = input.split(" ");
					
					if(inputArray.length == 2) {
						// inputArray[1] is the window size
						changeWindowSize(server, inputArray[1]);
					} else {
						System.out.println("Incorrect usage, must match: window <windowsize>");
					}
				}
				
				/*
				 * UNSUPPORTED COMMAND
				 */
				else {
					System.out.println("Unsupported command.");
				}
			}
		}
		
	}
	
}