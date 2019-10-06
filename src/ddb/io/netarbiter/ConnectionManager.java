package ddb.io.netarbiter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Stack;

import static ddb.io.netarbiter.Constants.*;

public class ConnectionManager
{
    private Map<Integer, Connection> activeConnections;
    private Stack<Integer> freeRemoteIDs;
    private int nextRemoteID = 0;
    private Selector channels;

    ConnectionManager()
    {
        this.activeConnections = new LinkedHashMap<>();
        this.freeRemoteIDs = new Stack<>();
    }

    public void init(Selector selector)
    {
        this.channels = selector;
    }

    public int allocateID(boolean isCommand)
    {
        int id;

        if (isCommand)
        {
            // Only 1 command connection is allowed
            id = -1;
        }
        else
        {
            // Allocate remote id
            if (!freeRemoteIDs.isEmpty())
                id = freeRemoteIDs.pop();
            else
                id = nextRemoteID++;
        }

        return id;
    }

    public void freeID(int connID)
    {
        if (connID >= 0)
        {
            // Free remote id
            freeRemoteIDs.push(connID);
        }
    }

    /**
     * Adds a connection and registers it with the channel selector
     * @param connection The connection to add
     * @param channel The connection's associated channel
     * @return The connection id of the new connection
     * @throws IOException If the channel couldn't be made unblocking
     */
    public int addConnection(Connection connection, SocketChannel channel) throws IOException
    {
        assert (connection != null && channel != null);
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        // Listen for both reads and writes
        channel.register(channels, SelectionKey.OP_READ | SelectionKey.OP_WRITE, connection);
        activeConnections.put((int) connection.getConnectionID(), connection);

        // Update the heartbeat to now
        connection.updateHeartbeat();

        return connection.getConnectionID();
    }

    public int addConnection(String hostname, int port)
    {
        try
        {
            // Connect to the remote host
            SocketAddress remoteAddr = new InetSocketAddress(hostname, port);
            SocketChannel channel = SocketChannel.open();
            channel.connect(remoteAddr);

            final ByteBuffer temp = ByteBuffer.allocateDirect(2);
            final byte[] readBytes = new byte[2];

            temp.clear();
            temp.put(CLIENT_MAGIC);
            temp.flip();

            // Send out the client magic
            channel.configureBlocking(true);
            channel.write(temp);

            // Expect the server magic back
            temp.flip();
            channel.read(temp);
            channel.configureBlocking(false);
            temp.flip();
            temp.get(readBytes);

            if (!Arrays.equals(readBytes, SERVER_MAGIC))
            {
                // Bad server magic
                return Constants.ARB_ERROR_CONNECT_REFUSED;
            }

            // Connection finalized, add to active connections
            return addConnection(new Connection((short)allocateID(false), channel), channel);
        }
        catch (UnresolvedAddressException e)
        {
            e.printStackTrace();
            // Unresolved address
            return Constants.ARB_ERROR_BAD_ADDRESS;
        }
        catch (ConnectException | ClosedChannelException e)
        {
            e.printStackTrace();
            // Connection Refused
            return Constants.ARB_ERROR_CONNECT_REFUSED;
        }
        catch (IOException e)
        {
            // Unknown error
            e.printStackTrace();
            return Constants.ARB_ERROR_UNKNOWN_ERROR;
        }
    }

    // ID -> Connection
    public Connection getConnection(int connID)
    {
        return activeConnections.get(connID);
    }

    /**
     * Closes a remote connection
     * @param connID The connection to close
     * @return The status of the closed connection
     */
    public int closeConnection(int connID)
    {
        Connection connection = activeConnections.get(connID);

        if (connection == null)
            return Constants.ARB_ERROR_INVALID_ID;

        // Close the connection
        connection.closeConnection();
        return 0;
    }

    public void pruneConnections()
    {
        activeConnections.values().removeIf((Connection::isClosed));
    }

    public void checkDeadConnections()
    {
        activeConnections.values().forEach((connection) -> {
            if (connection.isDead())
                connection.closeConnection();
        });
    }

    public Map<Integer, Connection> getActiveConnections()
    {
        return activeConnections;
    }
}
