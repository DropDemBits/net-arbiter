package ddb.io.netarbiter.packet;

import ddb.io.netarbiter.NetArbiter;

public abstract class CommandPacket extends Packet
{
    CommandPacket(int sequence)
    {
        super(sequence);
    }

    public abstract ResponsePacket execute(NetArbiter arbiter);

}
