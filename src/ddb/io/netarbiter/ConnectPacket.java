package ddb.io.netarbiter;

import java.nio.charset.StandardCharsets;

/**
 * Connection Command Packet
 * Initiates a connection to a remote arbiter
 *
 * Packet Format:
 * length:         2 bytes
 * packetSequence: 2 bytes (commands only)
 * packetID:       1 byte ('C')
 * port:           2 bytes
 * hostlen:        2 bytes
 * hostname:       "hostlen" bytes
 *
 * | 0    | 1   | 2   | 3    |
 * |     len    |    seq     |
 * | 'C'  |   port    | hLen |
 * | hLen |    hostname ...  |
 */
public class ConnectPacket extends CommandPacket
{
    public int port = 0;
    public String hostname = "garb";

    public ConnectPacket(int sequence) {
        super(sequence);
    }

    @Override
    public boolean parsePayload(byte[] payload)
    {
        System.out.println("Connecting to ...");

        // Fetch the port & hostname
        this.port = (Byte.toUnsignedInt(payload[0]) << 8) | Byte.toUnsignedInt(payload[1]);
        int hostLen = (Byte.toUnsignedInt(payload[2]) << 8) | Byte.toUnsignedInt(payload[3]);
        this.hostname = new String(payload, 4, hostLen, StandardCharsets.US_ASCII);

        return true;
    }

    @Override
    public ResponsePacket execute(NetArbiter arbiter)
    {
        int response = arbiter.addConnection(hostname, port);

        // Response code contains the connection id
        return new ResponsePacket(this.sequence, Constants.ARB_PACKET_ENDCMD, response);
    }
}
