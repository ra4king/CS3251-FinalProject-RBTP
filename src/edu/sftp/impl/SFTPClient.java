package edu.sftp.impl;

import edu.sftp.SFTP;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Socket;

/**
 * TODO Documentation
 * TODO ex: SFTP client commands (disconnect, get, put, etc)
 *
 * Note that this currently runs on top of TCP.
 * TODO - Change to run on top of RBTP.
 *
 * @author Evan
 */
public class SFTPClient {

    private final int    port;
    private final String netEmuIP;
    private final int    netEmuPort;

    // TODO - Switch to RBTP Socket
    private final Socket socket;

    private DataInputStream input;
    private DataOutputStream output;

    /**
     * Constructor for SFTPClient.
     *
     * TODO: Switch to use RBTP sockets
     *
     * TODO: Note we can get rid of DataStreams when using ByteBuffers in RBTP
     *
     * @param port          - Port on which SFTPClient is bound
     * @param netEmuIP      - IP address NetEmu is running on
     * @param netEmuPort    - Port NetEmu is bound to
     */
    public SFTPClient(int port, String netEmuIP, int netEmuPort) throws IOException {
        this.port = port;
        this.netEmuIP = netEmuIP;
        this.netEmuPort = netEmuPort;

        socket = new Socket(netEmuIP, netEmuPort);
        output = new DataOutputStream(socket.getOutputStream());
        input = new DataInputStream(socket.getInputStream());
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
     * Sends a file to the SFTP server via PUT.
     *
     * @param filename    - the file to PUT
     * @throws FileNotFoundException if the file is not found at the server.
     * @throws IOException if the connection is lost.
     */
    /*public boolean put(String filename) throws IOException {
        // TODO switch to RBTP (Bytestream)
        byte putRequest[] = SFTP.buildMessage(SFTP.PUT, filename.getBytes());
        byte response[];
        int responseLength;

        // Send GET
        output.write(putRequest);

        // Listen for response (IOException on timeout)
        responseLength = input.readInt();
        response = new byte[responseLength];

        input.readFully(response);

        // TODO Implement this when GET works with RBTP
        return true;
    }*/

    /**
     * Fetches a file from the SFTP server via GET.
     *
     * TODO - Switch to use RBTP sockets
     *
     * @param filename    - the file to GET
     * @return the requested file, in bytes.
     * @throws IOException if the connection is lost.
     */
    public byte[] get(String filename) throws IOException {
        byte getRequest[] = SFTP.buildMessage(SFTP.GET, filename.getBytes("UTF-8"));
        byte response[];
        int responseLength;

        // Send GET
        output.write(getRequest); // TODO: This fails after ONE GET (check after port to RBTP)

        // Listen for response
        responseLength = input.readInt();
        response = new byte[responseLength];

        input.readFully(response);

        return response;
    }

}
