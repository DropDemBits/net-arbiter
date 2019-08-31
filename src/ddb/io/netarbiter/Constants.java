package ddb.io.netarbiter;

public class Constants
{

    // Command packets
    public static final byte ARB_PACKET_CONNECT       = (byte) 'C';
    public static final byte ARB_PACKET_DISCONNECT    = (byte) 'D';
    public static final byte ARB_PACKET_STATUS        = (byte) 'S';
    public static final byte ARB_PACKET_WRITE         = (byte) 'W';
    public static final byte ARB_PACKET_EXIT          = (byte) 'X';

    // Remote packets
    public static final byte ARB_PACKET_READ          = (byte) 'R';

    // Response packets
    public static final byte ARB_PACKET_ENDCMD        = (byte) 'E';
    public static final byte ARB_PACKET_ENDCONN       = (byte) 'F';
    public static final byte ARB_PACKET_NEWCONN       = (byte) 'N';

    // Errors
    public static final int ARB_ERROR_NONE            =  0;
    public static final int ARB_ERROR_UNKNOWN_ERROR   = -1;
    public static final int ARB_ERROR_INVALID_ID      = -2;

    // Connection related
    public static final int ARB_ERROR_CONNECT_REFUSED = -3;
    public static final int ARB_ERROR_BAD_ADDRESS     = -4;
}
