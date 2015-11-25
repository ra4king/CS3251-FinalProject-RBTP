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
 *
 * @author Evan Bailey
 */
public class SFTPServer {

    private final int    port;
    private final String netEmuIP;
    private final int    netEmuPort;

    private final ServerSocket serverSocket;

    private boolean listen = true;

    private DataInputStream input;
    private DataOutputStream output;

    /**
     * Constructor for SFTPServer.
     *
     * TODO: Switch to RBTP sockets.
     *
     * @param port          - Port on which SFTPServer is bound
     * @param netEmuIP      - IP address NetEmu is running on
     * @param netEmuPort    - Port NetEmu is bound to
     */
    public SFTPServer(int port, String netEmuIP, int netEmuPort) throws IOException {
        this.port = port;
        this.netEmuIP = netEmuIP;
        this.netEmuPort = netEmuPort;

        serverSocket = new ServerSocket(port);
    }

    /**
     * Requests that RBTP use the provided window size.
     *
     * @param windowSize    - proposed window size
     */
    public synchronized void setWindowSize(int windowSize) {
        // TODO: Implement when RBTP sockets used
    }

    /**
     * Alerts the server to stop accepting new connections.
     *
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
        Socket clientSocket;
        Thread clientThread;

        while (listen) {
            clientSocket = serverSocket.accept();

            clientThread = new Thread(new ClientHandler(clientSocket));
            clientThread.setDaemon(false); // Any existing connections will finish before exiting
            clientThread.start();
        }

        serverSocket.close();
    }

    /**
     * TODO: Documentation
     *
     * TODO: Switch to RBTP sockets
     *
     * @author Evan Bailey
     */
    private class ClientHandler implements Runnable {
        Socket clientSocket;
        DataOutputStream output;
        DataInputStream input;

        /**
         * Constructor for ClientHandler.
         *
         * @param clientSocket  - connection to client
         * @throws IOException if one is encountered.
         */
        public ClientHandler(Socket clientSocket) throws IOException {
            this.clientSocket = clientSocket;
            output = new DataOutputStream(this.clientSocket.getOutputStream());
            input = new DataInputStream(this.clientSocket.getInputStream());

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
