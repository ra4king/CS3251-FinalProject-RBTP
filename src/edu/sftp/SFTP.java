package edu.sftp;

import java.nio.ByteBuffer;

/**
 * TODO - Documentation
 *
 * SFTP Message format
 * [data len][opcode][data ...]
 *  + data len: 4 bytes
 *  + opcode: 1 byte
 *  + data: maximum of (2^4) bytes
 *
 * @author Evan Bailey
 */
public class SFTP {

    /* SFTP Identifier Prefix */
    public static final short SFTP_PREFIX = (short)0xFADE;

    /* OPCODES */
    public static final byte ERR = 0x00;
    public static final byte GET = 0x01;
    public static final byte PUT = 0x02;
    public static final byte RSP = 0x03;


    /**
     * TODO Documentation
     * NOTE: Maximum filesize of (2^32) bytes
     */
    public static byte[] buildMessage(byte opcode, byte content[]) {
        int messageLength = 2 + 1 + content.length; // in bytes
        ByteBuffer bbuff = ByteBuffer.allocate(messageLength);

        bbuff.putShort(SFTP_PREFIX);
        bbuff.put(opcode);
        bbuff.put(content);

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