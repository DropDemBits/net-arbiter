package ddb.io.netarbiter;

public class Constants {

    // Packet IDs
    public static final int PCKTID_HEADER = 'a' << 24 | 'r' << 16 | 'b' << 8;
    public static final int PCKTID_CONNECT_ESTABLISH = 0;
    public static final int PCKTID_ACK = 1;
    public static final int PCKTID_DISCONNECT_NOTIFY = 2;

    // Arbiter Commands
    public static final byte ARB_CMD_EXIT =             'X';
    public static final byte ARB_CMD_CONNECT =          'C';
    public static final byte ARB_CMD_DISCONNECT =       'D';
    public static final byte ARB_CMD_SEND =             'P';

    // Arbiter Responses
    public static final byte ARB_REP_DATA_RECEIVED =    'G';
    public static final byte ARB_REP_NEW_CONNECTION =   'N';
    public static final byte ARB_REP_REMOTE_CLOSED =    'R';
    public static final byte ARB_REP_CMD_SUCCESSFUL =   'S';
    public static final byte ARB_REP_ERROR =            'W';

    // Arbiter Errors
    public static final short ARB_ERR_NONE = 0;
    public static final short ARB_ERR_CONNECTION_FAILED = 1;
    public static final short ARB_ERR_INVALID_ARG = 2;

    // Only for constant declarations
    private Constants() {}
}
