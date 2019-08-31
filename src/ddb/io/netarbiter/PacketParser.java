package ddb.io.netarbiter;

import java.util.Arrays;

public class PacketParser
{
    private PacketParser() {}

    public static Packet parsePacket(byte[] packetData)
    {
        // Arbiter Packet Format:
        // length:         2 bytes
        // packetSequence: 2 bytes (commands only)
        // packetID:       1 byte
        // payload:        length - 5 bytes

        // | 0   | 1   | 2   | 3   |
        // |    len    |    seq    |
        // | cID |   payload ...   |

        int packetLength = (packetData[0] << 8) | packetData[1];
        int packetSequence =  (packetData[2] << 8) | packetData[3];
        char packetID =    (char) packetData[4];
        byte[] payload;

        if ((packetLength - 5) > 0)
            payload = Arrays.copyOfRange(packetData, 5, packetLength);
        else
            payload = new byte[0];

        Packet packet = null;

        switch (packetID)
        {
            // Command packets
            case 'C': packet = new ConnectPacket(packetSequence);    break;
            case 'D': packet = new DisconnectPacket(packetSequence); break;
            case 'S': System.out.println("Fetching status of ...");  break; // StatusPacket
            case 'W': packet = new WritePacket(packetSequence);      break;
            case 'X': packet = new ExitPacket(packetSequence);       break;
            // Remote packets
            case 'R': packet = new ResponsePacket(packetSequence, Constants.ARB_PACKET_READ); break; // ReadPacket
            case 'F': packet = new ResponsePacket(packetSequence, Constants.ARB_PACKET_ENDCONN); break; // StatusNotifyPacket
        }

        if (packet != null)
            packet.parsePayload(payload);

        return packet;
    }

}
