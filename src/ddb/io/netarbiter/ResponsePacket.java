package ddb.io.netarbiter;

import java.util.Arrays;

/**
 * Packet sent from a remote arbiter to transfer data over
 *
 * Packet format:
 * length:         2 bytes
 * packetSequence: 2 bytes
 * packetID:       1 byte (varies)
 * src:            2 bytes -> Handled externally
 * data:           ("length" - 7) bytes
 *
 * | 0    | 1   | 2   | 3    |
 * |     len    |    seq     |
 * |  id  |    src    | ...  |
 * |           data          |
 */
public class ResponsePacket extends Packet
{
    public byte responseID;
    public byte[] responseData;

    public ResponsePacket(int sequence, byte responseID)
    {
        super(sequence);
        this.responseID = responseID;
    }

    public ResponsePacket(int sequence, byte responseID, int responseCode)
    {
        this(sequence, responseID);
        this.responseData = new byte[4];
        this.responseData[0] = (byte) ((responseCode >> 24) & 0xFF);
        this.responseData[1] = (byte) ((responseCode >> 16) & 0xFF);
        this.responseData[2] = (byte) ((responseCode >>  8) & 0xFF);
        this.responseData[3] = (byte) ((responseCode >>  0) & 0xFF);
    }

    public byte[] getPayload()
    {
        return responseData;
    }

    // Response packets can be recieved externally
    @Override
    public boolean parsePayload(byte[] payload)
    {
        this.responseData = Arrays.copyOf(payload, payload.length);
        return true;
    }

}
