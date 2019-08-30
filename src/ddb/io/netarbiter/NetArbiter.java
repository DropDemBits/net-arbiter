package ddb.io.netarbiter;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Network Arbiter for Turing Programs
 * Provides a simple networking interface to work around some of the bugs
 * inside of the Turing Net Module.
 *
 * Communication between the arbiter and the program is done via a TCP/IP
 * connection established in the Turing program.
 *
 */
public class NetArbiter {

    // Endpoint - Arbiter interface
    // Packet format:
    // packetID: 1 byte
    // sequence: 2 bytes (commands only)
    // length: 2 bytes
    // payload: length - 5 bytes

    // Available commands
    // 'S' Status (connID):              Fetches the status of a connection
    // \ SendBack: CommandAck + data -> Connection status
    // 'D' Disconnect (connID):          Disconnects an active connection
    // \ SendBack: CommandAck
    // 'C' Connect (port, hostname):     Connects to a remote arbiter
    // \ SendBack: CommandAck + data -> New connID
    // 'W' Write (connID, len, payload): Writes the payload data to the active connection
    // \ SendBack: CommandAck
    // 'X' Exit ():                      Shuts down the arbiter
    // \ SendBack: None

    // Responses
    // CommandAck (sequence): Indication of command completion
    // ConnectionChange (connID): Notification of a connection status change (New Connection, Disconnected)
    // Read (connID, len, payload): Packet recieved

    boolean isRunning = true;
    private int endpointPort, listenPort;
    private CommandConnection cmdConnection;

    private NetArbiter(int endpoint, int listen) {
        this.endpointPort = endpoint;
        this.listenPort = listen;

        // Connection manager
        this.activeConnections = new LinkedHashMap<>();
        this.freeRemoteIDs = new Stack<>();
    }

    ///   ConnectionManger Things   ///
    private Map<Integer, Connection> activeConnections;
    private Stack<Integer> freeRemoteIDs;
    private int nextRemoteID = 0;
    private Selector channels;
    private ServerSocketChannel arbiterServer;

    private int allocateID(boolean isCommand)
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

    private void freeID(int connID)
    {
        if (connID >= 0)
        {
            // Free remote id
            freeRemoteIDs.push(connID);
        }
    }

    /**
     * Adds a connection and registers it with the channel selector
     * @param connection
     * @param channel
     * @return
     * @throws IOException
     */
    public int addConnection(Connection connection, SocketChannel channel) throws IOException
    {
        assert (connection != null && channel != null);
        channel.configureBlocking(false);
        // Listen for both reads and writes
        channel.register(channels, SelectionKey.OP_READ | SelectionKey.OP_WRITE, connection);
        activeConnections.put((int) connection.getConnectionID(), connection);

        // Update the heartbeat to now
        connection.updateHeartbeat();

        return 0;
    }

    public int addConnection(String hostname, int port)
    {
        System.out.println("Connection add:" + hostname + ":" + port);
        return 0;
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
        System.out.println("Disconnecting to ...");
        return 0;
    }

    /// Utilty ///
    private void dumpBytes(byte[] data)
    {
        for (int i = 0; i < data.length; i++)
        {
            System.out.printf("%02X ", data[i]);

            if (i % 16 == 15)
                System.out.println();
        }
    }

    /// Arbiter ///
    private void initArbiter() throws IOException
    {
        arbiterServer = ServerSocketChannel.open();
        arbiterServer.bind(new InetSocketAddress(endpointPort));
    }

    private void processPackets(ByteBuffer readBuffer, SocketChannel channel, Connection connection, Queue<CommandPacket> commandQueue) throws IOException
    {
        // Parse the packet

        // Arbiter Packet Format
        // length:  2 bytes
        // payload: length - 2 bytes

        // Read in packets
        assert (!readBuffer.hasRemaining());
        readBuffer.clear();

        int amt = channel.read(readBuffer);
        if (amt == -1)
        {
            connection.closeConnection();
            return;
        }

        readBuffer.flip();

        // Process packets
        while (readBuffer.hasRemaining())
        {
            System.out.println("Data Recv: " + readBuffer.remaining());

            // Fetch initial packet length
            if (readBuffer.remaining() < 2)
            {
                readBuffer.compact();
                readBuffer.limit(2);
                channel.read(readBuffer);
                readBuffer.flip();
            }

            int packetLength = Short.toUnsignedInt(readBuffer.getShort());

            // Skip empty / heartbeat packets
            if (packetLength == 0)
                continue;

            // Fetch the rest of the packet if there's not enough data
            if (readBuffer.remaining() < (packetLength - 2))
            {
                // Read in the remaining packet
                readBuffer.position(readBuffer.position() - 2);
                readBuffer.compact();
                readBuffer.limit(packetLength);
                channel.read(readBuffer);
                assert (!readBuffer.hasRemaining());
                readBuffer.flip();
            }

            // Copy the entire packet
            byte[] data = new byte[packetLength];
            readBuffer.position(readBuffer.position() - 2);
            readBuffer.get(data);

            Packet packet = PacketParser.parsePacket(data);

            // Enqueue the command if the current connection is a write
            if (packet instanceof CommandPacket && connection instanceof CommandConnection)
                commandQueue.add((CommandPacket)packet);
        }

        // Update the heartbeat
        connection.updateHeartbeat();
    }

    private void processChannels() throws IOException
    {
        System.out.println("Waiting for connections");

        channels = Selector.open();
        SocketChannel endpoint = arbiterServer.accept();
        ByteBuffer poke = ByteBuffer.allocateDirect(2);
        ByteBuffer readBuffer = ByteBuffer.allocate(23);
        ByteBuffer writeBuffer = ByteBuffer.allocateDirect(2048);

        // Initialize the command connection
        cmdConnection = new CommandConnection((short)allocateID(true), endpoint);
        addConnection(cmdConnection, endpoint);

        Queue<CommandPacket> commandQueue = new LinkedBlockingDeque<>();

        while(isRunning)
        {
            // Process the pending command packets
            while (!commandQueue.isEmpty())
            {
                CommandPacket packet = commandQueue.remove();

                // TODO: Execute connect command:
                // \ Connect to a remote host
                //  \ Perform a connection w/ SocketChannel
                //  \ Allocate a connection id
                // \ Send back a response
                //  \ Send back the sequence id with the connID as payload (if success)
                //  \ Send back the sequence id with the error as payload (if failure)

                // TODO: Execute disconnect command:
                // \ Validate connID
                //  \ If invalid, make command invalid
                // \ Disconnect remote host
                //  \ Perform a disconnection w/ SocketChannel
                //  \ Free the connection id
                // \ Send back a response
                //  \ Send back the sequence id with the connID as payload (if success)
                //  \ Send back the sequence id with the error as payload (if failure)

                // TODO: Execute write command:
                // \ Validate connID
                //  \ If invalid, make command invalid
                // \ Send the data to the connection (or append on to channel's write queue)
                // \ Send back a response
                //  \ Send back the sequence id with 0 as payload (if success)
                //  \ Send back the sequence id with the error as payload (if failure)
                ResponsePacket response = packet.execute(this);
                // Enqueue the response
                cmdConnection.enqueueResponse(response);
            }

            // Process connection queues & check heartbeats
            for (Connection connection : activeConnections.values())
            {
                try
                {
                    assert (!writeBuffer.hasRemaining());
                    writeBuffer.clear();

                    while (!connection.writeQueue.isEmpty())
                    {
                        // Process all of the write packets (command -> remote)
                        // Clump as many writes as possible (for TCP connections)
                        WritePacket packet = connection.writeQueue.remove();

                        byte[] payload = packet.getPayload();
                        int dataLen = payload.length + 5;

                        if (writeBuffer.remaining() < dataLen)
                        {
                            // Send the current data out
                            writeBuffer.flip();
                            connection.channel.write(writeBuffer);
                            writeBuffer.clear();
                        }

                        // Check again if the data length is too big
                        if (writeBuffer.capacity() < dataLen)
                        {
                            // Allocate a new temporary buffer
                            ByteBuffer temp = ByteBuffer.allocateDirect(dataLen);

                            // Length
                            temp.putShort((short) dataLen);
                            // Sequence
                            temp.putShort((short) packet.sequence);
                            // PacketID ('R')
                            temp.put((byte) 'R');
                            // Payload
                            temp.put(payload);

                            // Write out the buffer
                            temp.flip();
                            connection.channel.write(temp);

                            // Move to the next packet
                            continue;
                        }

                        // Add on to the small queue
                        // Length
                        writeBuffer.putShort((short) dataLen);
                        // Sequence (ignored)
                        writeBuffer.putShort((short) 0);
                        // PacketID ('R')
                        writeBuffer.put((byte) 'R');
                        // Payload
                        writeBuffer.put(payload);

                        // If the queue will be empty, write out the remaining packets
                        if (connection.writeQueue.isEmpty())
                        {
                            writeBuffer.flip();
                            connection.channel.write(writeBuffer);
                        }
                    }

                    assert (!writeBuffer.hasRemaining());
                    writeBuffer.clear();

                    while (!connection.responseQueue.isEmpty())
                    {
                        // Process all of the response packets (remote -> command or arbiter -> command)
                        // Forward the responses to the command connection

                        // Clump as many responses as possible (for TCP connections)
                        ResponsePacket packet = connection.responseQueue.remove();

                        int dataLen = Integer.BYTES + Short.BYTES + 5;

                        if (writeBuffer.remaining() < dataLen)
                        {
                            // Send the current data out
                            writeBuffer.flip();
                            cmdConnection.channel.write(writeBuffer);
                            writeBuffer.clear();
                        }

                        // Length
                        writeBuffer.putShort((short) dataLen);
                        // Sequence (ignored)
                        writeBuffer.putShort((short) 0);
                        // PacketID ('E')
                        writeBuffer.put((byte) 'E');
                        // Source connection
                        // 0xFFFF/-1 means arbiter origin / command response
                        writeBuffer.putShort(connection.getConnectionID());
                        // Response code
                        writeBuffer.putInt(packet.responseCode);

                        // If the queue will be empty, write out the remaining packets
                        if (connection.writeQueue.isEmpty())
                        {
                            writeBuffer.flip();
                            cmdConnection.channel.write(writeBuffer);
                        }
                    }
                }
                catch (IOException e)
                {
                    // Exception occurred, close the connection
                    e.printStackTrace();
                    connection.closeConnection();
                }

                // Check if the connection is dead or closed
                if (connection.isDead() || connection.isClosed())
                {
                    if(connection.isDead())
                        System.out.println("Connection #" + connection.getConnectionID() + " died, closing");

                    // Perform cleanup
                    connection.closeConnection();
                    connection.channel.close();
                    freeID(connection.getConnectionID());
                    continue;
                }

                // Send the heartbeat
                if (!connection.isClosed() && !(connection instanceof CommandConnection))
                    connection.channel.write(poke);
            }

            // Prune all the dead connections
            activeConnections.values().removeIf((Connection::isClosed));

            // Check if the command connection was closed
            if (cmdConnection.isClosed())
            {
                System.out.println("Shutting down");
                break;
            }

            // Process inbound packets
            if(channels.select() <= 0)
                continue;

            Set<SelectionKey> keys = channels.selectedKeys();
            Iterator<SelectionKey> iterator = keys.iterator();

            // Go through all of the keys
            while(iterator.hasNext())
            {
                SelectionKey key = iterator.next();
                SocketChannel channel = (SocketChannel) key.channel();
                Connection connection = (Connection) key.attachment();

                try
                {
                    if (key.isAcceptable()) /* do a thing */ ;
                    if (key.isConnectable()) /* do a thing */ ;
                    if (key.isReadable()) processPackets(readBuffer, channel, connection, commandQueue);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    connection.closeConnection();
                }


                iterator.remove();
            }
        }
    }

    public void startArbiter()
    {
        try
        {
            initArbiter();
            processChannels();
        }
        catch (Exception e)
        {
            // Catch all exceptions
            e.printStackTrace();
        }
    }

    private static boolean parseArgs(String[] args, int[] ports) {
        int connectionPort = -1, listenPort = -1;

        for (String arg : args) {
            String[] components = arg.split("=");
            // Strip the "--"
            components[0] = components[0].replaceFirst("--", "");

            if (components.length != 2) {
                System.out.println("Invalid formatting: " + arg);
                return false;
            }

            switch (components[0]) {
                case "endpointPort":
                    connectionPort = Integer.parseInt(components[1]);
                    break;
                case "listenPort":
                    listenPort = Integer.parseInt(components[1]);
                    break;
                default:
                    System.out.println("Unknown argument \"" + components[0] + "\"");
                    return false;
            }
        }

        if (connectionPort == -1) {
            System.out.println("Connection port needs to be specified");
            return false;
        }

        if (connectionPort < 0 || connectionPort > 0xFFFF) {
            System.out.println("Connection port needs to be in the range of 0 - 65535");
            return false;
        }

        if (listenPort != -1 && listenPort < 0 || listenPort > 0xFFFF) {
            System.out.println("Listening port needs to be in the range of 0 - 65535");
            return false;
        }

        // Arguments successfuly parsed
        ports[0] = connectionPort;
        ports[1] = listenPort;
        return true;
    }

    public static void main(String[] args) {
        // Gather connection information
        if (args.length < 1 || args.length > 2) {
            System.out.println("Usage: arbiter [--endpointPort=[port]] (--listenPort=[port])");
            return;
        }

        // Acquire the ports
        int[] ports = new int[2];
        if(!parseArgs(args, ports)) {
            return;
        }

        // Launch the arbiter
        NetArbiter arbiter = new NetArbiter(ports[0], ports[1]);
        arbiter.startArbiter();

        /*try {
            System.out.println("Press any key to continue");
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }*/
    }

}
