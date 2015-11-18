package edu.sftp;

import java.io.File;

/**
 * TODO - documentation
 *
 * Note that not all header fields must be filled
 *
 * Sample SFTP
 *
 *
 * @author Evan Bailey
 */
public class SFTP {

    public static enum PacketType {GET, PUT, ERROR};

    public static final String SFTP_VERSION_INFO     = "SFTP 1.0";
    public static final String SFTP_FIELD_TERMINATOR = "\n";
    public static final String SFTP_FIELD_SEPARATOR  = ":";

    /* SFTP Commands / Control Fields*/
    public static final String SFTP_GET = "GET";
    public static final String SFTP_PUT = "PUT";
    public static final String SFTP_ERR = "ERR";

    /* SFTP Header Fields */
    public static final String SFTP_DATA_SIZE   = "DSIZE";
    public static final String SFTP_HEADER_SIZE = "HSIZE";
    public static final String SFTP_URL         = "URL";

    /**
     * TODO - documentation
     */
    public static String buildPacket(PacketType type, File file) {
        // TODO (should just do away with PacketType?)
        return null;
    }

}