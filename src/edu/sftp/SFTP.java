package edu.sftp;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * TODO - Documentation
 * TODO - Handle large files (size overflows int)
 * TODO - NOTE: Should respond to PUT before sending file?
 *
 * Note that not all header fields must be filled
 *
 * Sample SFTP:
 *     SFTP 1.0 | PUT\n
 *     PSIZE: 36
 *     URL: res\info\hello.txt
 *     CSIZE: 298
 *   [ HASH: 5117A81F29E35E45AE258E914B71510F ]
 *     CONTENT: section .data
 *              msg     db      'Hello world!', 0AH
 *              len     equ     $-msg
 *
 *              section .text
 *              global  _start
 *              _start: mov     edx, len
 *              mov     ecx, msg
 *              mov     ebx, 1
 *              mov     eax, 4
 *              int     80h
 * 
 *              mov     ebx, 0
 *              mov     eax, 1
 *              int     80h
 *
 * @author Evan Bailey
 */
public class SFTP {

    public static enum PacketType {ERROR, GET, PUT, RESPONSE};

    /* Structure */
    public static final String SFTP_VERSION_INFO      = "SFTP 1.0";
    public static final String SFTP_COMMAND_SEPARATOR = "|";
    public static final String SFTP_FIELD_SEPARATOR   = ":";
    public static final String SFTP_TERMINATION_SEQ   = "\n";

    /* Commands / Control Fields */
    public static final String SFTP_ERR = "ERR";
    public static final String SFTP_GET = "GET";
    public static final String SFTP_PUT = "PUT";
    public static final String SFTP_RSP = "RSP";

    /* Header Fields */
    public static final String SFTP_URL            = "URL";
    public static final String SFTP_PATH_SIZE      = "PSIZE";
    public static final String SFTP_CONTENT_SIZE   = "CSIZE";
    public static final String SFTP_HASHCODE       = "HASH";
    public static final String SFTP_CONTENT        = "CONTENT";
//  public static final String SFTP_ERR_MESSAGE    = "ERRMSG";

    /* Other */
    public static final String SFTP_DEFAULT_ERROR_MESSAGE = "Error encountered";


    /* SFTP packet helper class */
    /* TODO - maybe like make this just a little nicer...*/
    public static class SFTPPacket {
        public String URL          = null;
        public int    PATH_SIZE    = -1;
        public int    CONTENT_SIZE = -1;
        public String HASHCODE     = null;
        public byte   CONTENT[]    = null;
//      public String ERR_MESSAGE  = null;
    }

    /*
     * TODO - Documentation
     */
    public static final SFTPPacket parsePacket(byte packetBytes[]) throws IllegalArgumentException {
        if (null == packetBytes) {
            throw new IllegalArgumentException("Null parameters disallowed.");
        }

        // TODO
        return null;
    }

    /**
     * TODO - Documentation
     */
    public static byte[] buildPacket(PacketType packetType, String path, byte fileBytes[], String errMsg)
            throws IllegalArgumentException, IOException {
        if (null == packetType || null == path || null == fileBytes) {
            throw new IllegalArgumentException("Null parameters disallowed.");
        }

        byte fBytes[] = new byte[0]; // Initialized to an empty array
        byte hBytes[];
        StringBuilder headerString = new StringBuilder();
        ByteBuffer bbuff;

        // First line, version info and command
        headerString.append(SFTP_VERSION_INFO + " " + SFTP_COMMAND_SEPARATOR + " ");

        switch (packetType) {
            case ERROR:
                headerString.append(SFTP_ERR + SFTP_TERMINATION_SEQ);

                if (null == errMsg) {
                    errMsg = SFTP_DEFAULT_ERROR_MESSAGE;
                }

                // Error Message
                headerString.append(SFTP_CONTENT_SIZE + SFTP_FIELD_SEPARATOR + errMsg.getBytes().length);
//              headerString.append(SFTP_ERR_MESSAGE + SFTP_FIELD_SEPARATOR);
                headerString.append(SFTP_CONTENT + SFTP_FIELD_SEPARATOR);
                headerString.append(errMsg + SFTP_TERMINATION_SEQ);

                break;

            case GET:
                headerString.append(SFTP_GET + SFTP_TERMINATION_SEQ);

                // Path
                headerString.append(SFTP_PATH_SIZE + SFTP_FIELD_SEPARATOR + path.getBytes().length);
                headerString.append(SFTP_TERMINATION_SEQ);
                headerString.append(SFTP_URL + SFTP_FIELD_SEPARATOR + path);
                headerString.append(SFTP_TERMINATION_SEQ);

                break;

            case PUT:
                headerString.append(SFTP_PUT + SFTP_TERMINATION_SEQ);

                // File Bytes
                fBytes = fileBytes;

                // Path
                headerString.append(SFTP_PATH_SIZE + SFTP_FIELD_SEPARATOR + path.getBytes().length);
                headerString.append(SFTP_TERMINATION_SEQ);
                headerString.append(SFTP_URL + SFTP_FIELD_SEPARATOR + path);
                headerString.append(SFTP_TERMINATION_SEQ);

                // File size
                headerString.append(SFTP_CONTENT_SIZE + SFTP_FIELD_SEPARATOR + fBytes.length);
                headerString.append(SFTP_TERMINATION_SEQ);
                headerString.append(SFTP_CONTENT + SFTP_FIELD_SEPARATOR);

                break;

            case RESPONSE:
                headerString.append(SFTP_RSP + SFTP_TERMINATION_SEQ);

                // File Bytes
                fBytes = fileBytes;

                 // Path
                headerString.append(SFTP_PATH_SIZE + SFTP_FIELD_SEPARATOR + path.getBytes().length);
                headerString.append(SFTP_TERMINATION_SEQ);
                headerString.append(SFTP_URL + SFTP_FIELD_SEPARATOR + path);
                headerString.append(SFTP_TERMINATION_SEQ);

                // File size
                headerString.append(SFTP_CONTENT_SIZE + SFTP_FIELD_SEPARATOR + fBytes.length);
                headerString.append(SFTP_TERMINATION_SEQ);
                headerString.append(SFTP_CONTENT + SFTP_FIELD_SEPARATOR);

                break;
        }

        // Size of header is known now
        hBytes = headerString.toString().getBytes();
        bbuff = ByteBuffer.allocate(hBytes.length + fBytes.length);

        bbuff.put(hBytes);
        bbuff.put(fBytes);

        return bbuff.array();
    }

    // /*
    //  * Temporary for testing. comment out package if testing like this.
    //  */
    // public static void main(String args[]) {
    //     byte packetBytes[];
    //     String packetString = new String();
    //     try {
    //         Path path = Paths.get("..\\..\\..\\test\\hello-asm.txt");
    //         packetBytes = Files.readAllBytes(path);
    //         System.out.println("file: " + path.toRealPath().toString());
    //         System.out.println("size: " + Files.size(path) + " bytes");
    //         System.out.println("raw bytes:");
    //         for (byte b : packetBytes) {
    //             System.out.print(b + "\t");
    //         }
    //         Path path2 = Paths.get("..\\..\\..\\test\\hello-asm2.txt");
    //         path2 = Files.createFile(path2);
    //         File file = path2.toFile();
    //         FileOutputStream fouts = new FileOutputStream(file);
    //         fouts.write(packetBytes);
    //         fouts.close();
    //     }
    //     catch (Exception ex) {
    //         ex.printStackTrace();
    //     }
    // }

}