package ddb.io.netarbiter;

public abstract class CommandPacket extends Packet
{
    CommandPacket(int sequence)
    {
        super(sequence);
    }

    public abstract ResponsePacket execute(NetArbiter arbiter);

}
