package edu.sftp.impl;

import edu.sftp.SFTP;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * TODO Documentation
 * TODO ex: SFTP server commands (disconnect, window)
 *
 * Note that this currently runs on top of TCP.
 * TODO - Change to run on top of RBTP.
 *
 * @author Evan
 */
public class SFTPServer {

    private final int    port;
    private final String netEmuIP;
    private final int    netEmuPort;

    // TODO - Switch to RBTP Socket
    private final ServerSocket serverSocket;

    private DataInputStream input;
    private DataOutputStream output;

    /**
     * Constructor for SFTPServer.
     *
     * @param port          - Port on which SFTPServer is bound
     * @param netEmuIP      - IP address NetEmu is running on
     * @param netEmuPort    - Port NetEmu is bound to
     */
    public SFTPServer(int port, String netEmuIP, int netEmuPort) throws IOException {
        this.port = port;
        this.netEmuIP = netEmuIP;
        this.netEmuPort = netEmuPort;

        // TODO how can we get this working with NetEmu support?
        serverSocket = new ServerSocket(port);
    }

    /**
     * TODO Documentation
     *
     * @throws IOException
     */
    public void listen() throws IOException {
        Socket clientSocket;

        // TODO Documentation
        System.out.println("Listening for connections");

        while (true) {
            clientSocket = serverSocket.accept();

            new ClientHandler(clientSocket).run();
        }
    }

    /**
     * TODO Documentation
     */
    private class ClientHandler implements Runnable {
        Socket clientSocket;
        DataOutputStream output;
        DataInputStream input;

        public ClientHandler(Socket clientSocket) throws IOException {
            this.clientSocket = clientSocket;
            output = new DataOutputStream(this.clientSocket.getOutputStream());
            input = new DataInputStream(this.clientSocket.getInputStream());

            // TODO DEBUG
            System.out.println("Accepted client connection");
        }

        private byte[] handleGet(byte content[]) {
            byte response[];
            String errorMessage, filename;
            Path path;

            try {
                filename = new String(content, "UTF-8");

                path = Paths.get(filename);
                if (Files.exists(path)) {
                    response = SFTP.buildMessage(SFTP.RSP, Files.readAllBytes(path));
                }
                else {
                    errorMessage = "File not found";
                    response = SFTP.buildMessage(SFTP.ERR, errorMessage.getBytes("UTF-8"));
                }
            }
            catch (UnsupportedEncodingException ueex) {
                // Never reached
                errorMessage = "Server does not support UTF-8 encoding";
                response = SFTP.buildMessage(SFTP.ERR, errorMessage.getBytes());
            }
            catch (IOException ioex) {
                // Never reached due to checking that file exists
                errorMessage = "IOException encountered while reading file";
                response = SFTP.buildMessage(SFTP.ERR, errorMessage.getBytes());
            }

            return response;
        }

        /**
         * Handle the client connection
         */
        public void run() {
            int msgLength;
            byte opcode;
            byte message[], content[];

            try {
                msgLength = input.readInt();
                message = new byte[msgLength];
                content = new byte[msgLength - 1]; // First byte of message is opcode

                input.readFully(message);

                // Get opcode
                opcode = message[0];

                // Get content
                for (int i = 1; i < msgLength; i++) {
                    content[i - 1] = message[i];
                }

                if (SFTP.GET == opcode) {
                    output.write(handleGet(content));
                }
                else if (SFTP.PUT == opcode) {
                    // TODO Implement (extra credit)
                }

                // Close socket when done
                clientSocket.close();
            }
            catch (IOException ioex) {
                System.out.println("Lost connection to client while receiving message");
            }
        }

    }

}
