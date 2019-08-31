package ddb.io.netarbiter;

import java.util.Arrays;

/**
 * Write Command Packet
 * Writes data to a remote connection
 *
 * Packet Format:
 * length:         2 bytes
 * packetSequence: 2 bytes (commands only)
 * packetID:       1 byte ('W')
 * dest:           2 bytes
 * data:           "length" - 7 bytes
 *
 * | 0   | 1   | 2   | 3    |
 * |    len    |    seq     |
 * | 'W' |   dest    | data |
 * |        data ...        |
 */
public class WritePacket extends CommandPacket
{
    private short connID;
    private byte[] payload;

    public WritePacket(int packetSequence)
    {
        super(packetSequence);
    }

    public byte[] getPayload()
    {
        return payload;
    }

    @Override
    public boolean parsePayload(byte[] payload)
    {
        this.connID = (short) ((Byte.toUnsignedInt(payload[0]) << 8) | Byte.toUnsignedInt(payload[1]));
        this.payload = Arrays.copyOfRange(payload, 2, payload.length);

        return true;
    }

    @Override
    public ResponsePacket execute(NetArbiter arbiter)
    {
        //System.out.println("Writting packet to #" + connID);
        Connection connection = arbiter.getConnection(connID);

        // Return an error code for the response
        if (connection == null)
            return new ResponsePacket(this.sequence, Constants.ARB_PACKET_ENDCMD, Constants.ARB_ERROR_INVALID_ID);

        // Enqueue the write with the appropriate connection
        connection.enqueueWrite(this);
        // Return a command success
        return new ResponsePacket(this.sequence, Constants.ARB_PACKET_ENDCMD, 0);
    }

}
