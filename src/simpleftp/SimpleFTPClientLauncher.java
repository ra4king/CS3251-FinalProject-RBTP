package simpleftp;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;

import edu.rbtp.impl.NetworkManager;
import simpleftp.impl.SimpleFTPClient;

/**
 * TODO Documentation
 * TODO Format console output to look nicer
 *
 * @author Evan Bailey
 */
public class SimpleFTPClientLauncher {
	
	private static String determineLocalFilename(String filename) {
		int count = 1;
		String localFilename = filename;
		
		while(Files.exists(Paths.get(localFilename))) {
			localFilename = filename.concat("(" + String.valueOf(count) + ")");
			count++;
		}
		
		return localFilename;
	}
	
	private static void changeWindowSize(SimpleFTPClient client, String windowSizeStr) {
		int windowSize;
		
		try {
			windowSize = Integer.parseInt(windowSizeStr);
			
			client.setWindowSize(windowSize);
		}
		catch(NumberFormatException nfex) {
			System.out.println("Incorrect parameters, window size must be integer.");
		}
	}
	
	private static void doGet(SimpleFTPClient client, String filename) {
		byte opcode;
		byte response[], content[];
		FileOutputStream fouts;
		String localFilename, errorMessage;
		
		try {
			response = client.get(filename);
			content = new byte[response.length - 1];
			opcode = response[0];
			
			System.arraycopy(response, 1, content, 0, content.length);
			
			// Successful GET
			if(SimpleFTP.RSP == opcode) {
				System.out.println("Successfully received file from server.");
				
				// Ensure we don't overwrite pre-existing files
				localFilename = determineLocalFilename(filename);
				
				// Create file
				fouts = new FileOutputStream(localFilename);
				
				fouts.write(content);
				fouts.close();
				
				System.out.println("File saved as " + localFilename);
			}
			// Unsuccessful GET
			else if(SimpleFTP.ERR == opcode) {
				System.out.print("Server returned an error message (length " + content.length + " bytes):");
				errorMessage = new String(content, "UTF-8");
				
				System.out.println(errorMessage);
			}
		}
		catch(IOException ioex) {
			System.out.println("IOException encountered while attempting GET, aborting.");
			
			// TODO: Temp
			ioex.printStackTrace();
		}
	}
	
	/**
	 * Entry-point for execution.
	 * <p>
	 * To run:
	 * $ java SFTPClient X A P
	 * X: Even port number SFTPClient will bind to. Must be server port - 1.
	 * A: IP address of NetEmu
	 * P: UDP port number of NetEmu
	 *
	 * @param args - command-line arguments
	 */
	public static void main(String args[]) {
		boolean run = true, connected = false;
		Scanner scanner = new Scanner(System.in);
		
		int port, netEmuPort;
		String netEmuIP, input, filename;
		String inputArray[];
		SimpleFTPClient client = null;
		
		if(args.length != 3) {
			System.out.println("ERROR: Incorrect parameters. Usage: $ java SFTPClient X A P");
			System.exit(0);
		}
		
		try {
			// Parse arguments, create client
			port = Integer.parseInt(args[0]);
			NetworkManager.init(port);
			
			netEmuIP = args[1];
			netEmuPort = Integer.parseInt(args[2]);
			
			// Listen for commands
			while(run) {
				System.out.print("> ");
				input = scanner.nextLine();
				
				/*
				 * CONNECT COMMAND
				 */
				if(input.equalsIgnoreCase("connect")) {
					if(connected) {
						System.out.println("ERROR: Client already connected to server.");
					} else {
						client = new SimpleFTPClient(port, netEmuIP, netEmuPort);
						connected = true;
						
						System.out.println("Connected to server.");
					}
				}
				
				/*
				 * DISCONNECT COMMAND
				 */
				else if(input.equalsIgnoreCase("disconnect")) {
					System.out.println("Shutting down client.");
					// TODO client.kill() ?
					run = false;
				}
				
				/*
				 * WINDOW COMMAND
				 */
				else if(input.toLowerCase().startsWith("window ")) {
					if(connected) {
						// Get filename argument
						inputArray = input.split(" ");
						
						if(inputArray.length == 2) {
							// inputArray[1] is the window size
							changeWindowSize(client, inputArray[1]);
						} else {
							System.out.println("ERROR: Incorrect usage, must match: window <windowsize>");
						}
					} else {
						System.out.println("ERROR: Client not currently connected.");
					}
				}
				
				/*
				 * GET COMMAND
				 */
				else if(input.toLowerCase().startsWith("get ")) {
					if(connected) {
						// Get filename argument
						inputArray = input.split(" ");
						
						if(inputArray.length == 2) {
							// inputArray[1] is the filename
							doGet(client, inputArray[1]);
						} else {
							System.out.println("ERROR: Incorrect usage, must match: get <filename>");
						}
					} else {
						System.out.println("ERROR: Client not currently connected.");
					}
				}
				
				/*
				 * PUT / POST COMMAND
				 */
				else if(input.toLowerCase().startsWith("put ")
				          || input.toLowerCase().startsWith("post ")) {
					// TODO - Implement
				}

				/*
				 * UNSUPPORTED COMMAND
				 */
				else {
					System.out.println("ERROR: Unsupported command.");
				}
			}
		}
		catch(NumberFormatException nfex) {
			System.out.println("Port arguments must be integers.");
			System.exit(0);
		}
		catch(ConnectException cex) {
			System.out.println("Connection to server lost (or no connection could be made).");
			System.out.println("Server may have closed.");
			System.exit(0);
		}
		catch(SocketException sex) {
			System.out.println("Socket creation failed. Is your IP / port correct?");
			System.exit(0);
		}
		catch(IOException ioex) {
			System.out.println("An IOException was encountered while the client was running.");
			System.out.println("Please restart client and try again.");
			System.exit(0);
		}
	}
	
}
