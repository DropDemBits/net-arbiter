package ddb.io.netarbiter;

/**
 * Disconnect Command Packet
 * Initiates the disconnection of a remote connection
 *
 * Packet Format:
 * length:         2 bytes
 * packetSequence: 2 bytes (commands only)
 * packetID:       1 byte ('D')
 * connID:         2 bytes
 *
 * | 0   | 1   | 2   | 3    |
 * |    len    |    seq     |
 * | 'D' |   connID  |
 */
public class DisconnectPacket extends CommandPacket
{
    private int connID;

    public DisconnectPacket(int packetSequence)
    {
        super(packetSequence);
    }

    @Override
    public boolean parsePayload(byte[] payload)
    {
        if (payload.length != 2)
            return false;

        this.connID = (Byte.toUnsignedInt(payload[0]) << 8) | Byte.toUnsignedInt(payload[1]);
        return true;
    }

    @Override
    public ResponsePacket execute(NetArbiter arbiter)
    {
        return new ResponsePacket(this.sequence, (byte) 'E', arbiter.closeConnection(connID));
    }

}
