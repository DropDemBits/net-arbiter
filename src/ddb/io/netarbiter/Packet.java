package ddb.io.netarbiter;

public abstract class Packet
{

    public final int sequence;

    public Packet(int sequence)
    {
        this.sequence = sequence;
    }

    public abstract boolean parsePayload(byte[] payload);

}
