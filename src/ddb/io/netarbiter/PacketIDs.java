package ddb.io.netarbiter;

public class PacketIDs {

    public static final int PCKTID_HEADER = 'a' << 24 | 'r' << 16 | 'b' << 8;
    public static final int PCKTID_CONNECT_ESTABLISH = 1;
    public static final int PCKTID_ACK = 2;
    public static final int PCKTID_DISCONNECT_NOTIFY = 2;

}
