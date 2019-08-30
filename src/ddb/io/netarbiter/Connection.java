package ddb.io.netarbiter;

import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public abstract class Connection
{
    // More than 10000ms between heartbeats = dead
    public static final long ARREST_TIMER = 10000;

    private boolean isActive;
    private short connID;
    public Queue<WritePacket> writeQueue;
    public Queue<ResponsePacket> responseQueue;
    public SocketChannel channel;
    // The last time the heartbeat was received or sent, as a timestamp in
    // milliseconds
    private long lastHeartbeat;

    /**
     * Creates a new connection
     * @param connID The connection id of the connection
     * @param channel The socket channel associated with the connection
     */
    public Connection (short connID, SocketChannel channel)
    {
        this.connID = connID;
        this.isActive = true;
        this.writeQueue = new LinkedBlockingQueue<>();
        this.responseQueue = new LinkedBlockingQueue<>();
        this.channel = channel;
    }

    public short getConnectionID()
    {
        return connID;
    }

    public void closeConnection()
    {
        isActive = false;
    }

    public boolean isClosed()
    {
        return !isActive;
    }

    /**
     * Adds a pending write to the write queue
     * @param pendingWrite The packet representing the pending write
     */
    public void enqueueWrite(WritePacket pendingWrite)
    {
        writeQueue.add(pendingWrite);
    }

    /**
     * Adds a pending response to the response queue
     * @param response The packet representing the pending response
     */
    public void enqueueResponse(ResponsePacket response)
    {
        responseQueue.add(response);
    }

    /**
     * Updates the current heartbeat timestamp
     */
    public void updateHeartbeat()
    {
        lastHeartbeat = System.currentTimeMillis();
    }

    /**
     * Checks if the connection hasn't sent a heartbeat
     * @return If the connection is dead or not
     */
    public boolean isDead()
    {
        return (System.currentTimeMillis() - lastHeartbeat) > ARREST_TIMER;
        //return false;
    }

}
