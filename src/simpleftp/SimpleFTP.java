package simpleftp;

import java.nio.ByteBuffer;

/**
 * SFTP protocol definitions
 *
 * SFTP Message format
 * [data len][opcode][data ...]
 *  + data len: 4 bytes
 *  + opcode: 1 byte
 *  + data: maximum of (2^4) bytes
 *
 * @author Evan Bailey
 */
public class SimpleFTP {

    /* SFTP Identifier Prefix */
    public static final short SFTP_PREFIX = (short)0xFADE;

    /* OPCODES */
    public static final byte ERR = 0x00;
    public static final byte GET = 0x01;
    public static final byte PUT = 0x02;
    public static final byte RSP = 0x03;


    /**
     * Helper function to build an SFTP message.
     *
     * NOTE: Maximum filesize of (2^32) bytes
     */
    public static byte[] buildMessage(byte opcode, byte content[]) {
        int messageLength = 1 + content.length; // in bytes, opcode + content
        ByteBuffer bbuff = ByteBuffer.allocate(4 + messageLength); // +4 makes room for length

        //bbuff.putShort(SFTP_PREFIX);
        bbuff.putInt(messageLength);
        bbuff.put(opcode);
        bbuff.put(content);

        return bbuff.array();
    }
}
