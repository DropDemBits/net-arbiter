package ddb.io.netarbiter;

/**
 * Packet sent in response to a command or a status change
 *
 * Packet format:
 * length:         2 bytes
 * packetSequence: 2 bytes
 * packetID:       1 byte ('E')
 * src:            2 bytes -> Handled externally
 * responseCode:   4 bytes
 *
 * | 0    | 1   | 2   | 3    |
 * |     len    |    seq     |
 * | 'E'  |    src    | rspC |
 * |   responseCode   |      |
 */
public class ResponsePacket extends Packet
{
    public int responseCode;

    public ResponsePacket(int sequence, int responseCode)
    {
        super(sequence);
        this.responseCode = responseCode;
    }

    // Response packets will not be received externally
    @Override
    public boolean parsePayload(byte[] payload)
    {
        return false;
    }

}
