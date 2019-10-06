package ddb.io.netarbiter.packet;

import ddb.io.netarbiter.Constants;
import ddb.io.netarbiter.NetArbiter;

/**
 * Exit Command Packet
 * Initiates the shutdown of the arbiter
 *
 * Packet Format:
 * length:         2 bytes
 * packetSequence: 2 bytes (commands only)
 * packetID:       1 byte ('X')
 * payload:        0 bytes
 *
 * | 0    | 1   | 2   | 3    |
 * |     len    |    seq     |
 * | 'X'  |    payload ...   |
 */
public class ExitPacket extends CommandPacket
{
    public ExitPacket(int packetSequence)
    {
        super(packetSequence);
    }

    @Override
    public boolean parsePayload(byte[] payload)
    {
        return payload.length == 0;
    }

    @Override
    public ResponsePacket execute(NetArbiter arbiter)
    {
        arbiter.getConnectionManager().getConnection(-1).closeConnection();
        // Will not be used, but add one just in case
        return new ResponsePacket(this.sequence, Constants.ARB_PACKET_ENDCMD, 0);
    }

}
