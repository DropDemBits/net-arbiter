package ddb.io.netarbiter;

import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class Connection
{
    // More than 10000ms between heartbeats = dead
    public static final long ARREST_TIMER = 10000;

    private boolean isCommand;
    private boolean isActive;
    private short connID;
    public Queue<WritePacket> writeQueue;
    public Queue<ResponsePacket> responseQueue;
    public SocketChannel channel;
    // The last time the heartbeat was received or sent, as a timestamp in
    // milliseconds
    private long lastHeartbeat;
    private long lastHeartbeatSent;

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

    public void setAsCommandConnection(boolean isCommand)
    {
        this.isCommand = isCommand;
    }

    public boolean isCommandConnection()
    {
        return isCommand;
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
     * Updates the current received heartbeat timestamp
     */
    public void updateHeartbeat()
    {
        lastHeartbeat = System.currentTimeMillis();
    }

    /**
     * Updates the last sent heartbeat
     */
    public void updateSentHeartbeat()
    {
        lastHeartbeatSent = System.currentTimeMillis();
    }

    /**
     * Gets the time since the last heartbeat was sent
     * @return The time since the last heartbeat
     */
    public long getLastSentBeat()
    {
        return System.currentTimeMillis() - lastHeartbeatSent;
    }

    /**
     * Checks if the connection hasn't sent a heartbeat
     * If the connection is a command connection, it will never die
     * @return If the connection is dead or not
     */
    public boolean isDead()
    {
        return !isCommand && (System.currentTimeMillis() - lastHeartbeat) > ARREST_TIMER;
    }

}
