package edu.sftp;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

/**
 * TODO - documentation
 *
 * Note that not all header fields must be filled
 *
 * Sample SFTP:
 *     SFTP 1.0 | GET\n
 *     URL: res/info/hello.txt
 *     HSIZE: idk something
 *     CSIZE: 300
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

    public static enum PacketType {GET, PUT, ERROR};

    /* Structure */
    public static final String SFTP_VERSION_INFO      = "SFTP 1.0";
    public static final String SFTP_COMMAND_SEPARATOR = "|";
    public static final String SFTP_FIELD_SEPARATOR   = ":";
    public static final String SFTP_FIELD_TERMINATOR  = "\n";

    /* Commands / Control Fields */
    public static final String SFTP_GET = "GET";
    public static final String SFTP_PUT = "PUT";
    public static final String SFTP_ERR = "ERR";

    /* Header Fields */
    public static final String SFTP_URL            = "URL";
    public static final String SFTP_HEADER_SIZE    = "HSIZE";
    // TODO - Overflow integer for sizes denoting (MAX_INT * overflow) + size
    public static final String SFTP_CONTENT_SIZE   = "CSIZE";
    public static final String SFTP_HASHCODE       = "HASH";
    public static final String SFTP_CONTENT        = "CONTENT";

    /**
     * TODO - documentation
     */
    public static String buildPacket(PacketType type, Path path) {
        // TODO (should just do away with PacketType?)
        // byte[] bytes = Files.readAllBytes(path);
        return null;
    }

}