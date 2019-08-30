package ddb.io.netarbiter;

import java.nio.channels.SocketChannel;

public class CommandConnection extends Connection
{
    public CommandConnection(short connID, SocketChannel channel)
    {
        super(connID, channel);
    }

}
