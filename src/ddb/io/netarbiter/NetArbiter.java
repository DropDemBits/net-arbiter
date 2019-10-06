package ddb.io.netarbiter;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
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

    private boolean isRunning = true;
    private int endpointPort, listenPort;
    private Connection cmdConnection;
    private static final byte[] CLIENT_MAGIC = new byte[] { (byte) 0xAB, (byte) 0x1C };
    private static final byte[] SERVER_MAGIC = new byte[] { (byte) 0xCA, (byte) 0xC0 };

    // Minimum 100ms between heartbeats
    public static final long HEARBEAT_INTERVAL = 100;

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
    private ServerSocketChannel endpointServer;
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
        System.out.println("Connection add " + hostname + ":" + port);

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
        System.out.println("Disconnecting to ...");
        Connection connection = activeConnections.get(connID);
        
        if (connection == null)
            return Constants.ARB_ERROR_INVALID_ID;
        
        // Close the connection
        connection.closeConnection();
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
        endpointServer = ServerSocketChannel.open();
        endpointServer.bind(new InetSocketAddress(endpointPort));

        if (listenPort != -1)
        {
            arbiterServer = ServerSocketChannel.open();
            arbiterServer.bind(new InetSocketAddress(listenPort));
        }
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
            // Connection has been closed
            connection.closeConnection();
            return;
        }

        readBuffer.flip();

        // Process packets
        while (readBuffer.hasRemaining())
        {
            //System.out.println("Data Recv: " + readBuffer.remaining());

            // Check if enough time has passed since the last heartbeat was sent
            if (!connection.isCommandConnection() && connection.getLastSentBeat() > HEARBEAT_INTERVAL)
            {
                final ByteBuffer poke = ByteBuffer.allocateDirect(2);
                poke.clear();
                channel.write(poke);
                connection.updateSentHeartbeat();
            }

            // Fetch initial packet length
            if (readBuffer.remaining() < 2)
            {
                readBuffer.compact();
                readBuffer.limit(2);
                channel.read(readBuffer);
                readBuffer.flip();
            }

            int packetLength = Short.toUnsignedInt(readBuffer.getShort());

            // Update the recieved heartbeat
            connection.updateHeartbeat();

            // Skip empty / heartbeat packets
            if (packetLength == 0)
                continue;

            ByteBuffer processBuffer;

            // Allocate a bigger buffer if the current one can't hold the data
            if (readBuffer.capacity() < (packetLength - 2))
            {
                readBuffer.position(readBuffer.position() - 2);
                readBuffer.compact();
                readBuffer.flip();

                processBuffer = ByteBuffer.allocate(packetLength);
                processBuffer.put(readBuffer);
                channel.read(processBuffer);
                processBuffer.flip();
                processBuffer.position(2);
            }
            // Fetch the rest of the packet if there's not enough data
            else if (readBuffer.remaining() < (packetLength - 2))
            {
                // Read in the remaining packet
                readBuffer.position(readBuffer.position() - 2);
                readBuffer.compact();
                readBuffer.limit(packetLength);
                channel.read(readBuffer);
                assert (!readBuffer.hasRemaining());
                readBuffer.flip();
                readBuffer.position(2);
                processBuffer = readBuffer;
            }
            else {
                processBuffer = readBuffer;
            }

            //System.out.println("Dl " + processBuffer.remaining() + ", " + packetLength);

            // Copy the entire packet
            byte[] data = new byte[packetLength];
            processBuffer.position(processBuffer.position() - 2);
            processBuffer.get(data);

            Packet packet = PacketParser.parsePacket(data);

            // Enqueue the command if the current connection is a write
            if (packet instanceof CommandPacket && connection.isCommandConnection())
                commandQueue.add((CommandPacket)packet);
            else if (packet instanceof ResponsePacket)
                connection.enqueueResponse((ResponsePacket) packet);
        }

        connection.updateHeartbeat();
    }

    private void processChannels() throws IOException
    {
        System.out.println("Waiting for connections");

        channels = Selector.open();
        SocketChannel endpoint = endpointServer.accept();
        ByteBuffer poke = ByteBuffer.allocateDirect(2);
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(256);
        ByteBuffer writeBuffer = ByteBuffer.allocateDirect(1024);

        // Initialize the command connection
        cmdConnection = new Connection((short)allocateID(true), endpoint);
        cmdConnection.setAsCommandConnection(true);
        addConnection(cmdConnection, endpoint);

        if (listenPort != -1)
        {
            // Add the arbiter server
            arbiterServer.configureBlocking(false);
            arbiterServer.register(channels, SelectionKey.OP_ACCEPT);
        }

        Queue<CommandPacket> commandQueue = new LinkedBlockingDeque<>();

        System.out.println("Connection with endpoint established");

        while(isRunning)
        {
            // Process the pending command packets
            while (!commandQueue.isEmpty())
            {
                CommandPacket packet = commandQueue.remove();

                // Execute connect command:
                // \ Connect to a remote host
                //  \ Perform a connection w/ SocketChannel
                //  \ Allocate a connection id
                // \ Perform version handshake
                //  \ If incompat, fail conn
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
                //  \ Send back the sequence id with 0 as payload (if success)
                //  \ Send back the sequence id with the error as payload (if failure)

                // Execute write command:
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
                    // Send the heartbeat
                    if (!connection.isClosed() && !connection.isCommandConnection() && connection.getLastSentBeat() > HEARBEAT_INTERVAL)
                    {
                        poke.clear();
                        connection.channel.write(poke);
                        connection.updateSentHeartbeat();
                    }

                    assert (!writeBuffer.hasRemaining());
                    writeBuffer.clear();

                    while (!connection.responseQueue.isEmpty())
                    {
                        // Process all of the response packets (remote -> command or arbiter -> command)
                        // Forward the responses to the command connection

                        // Clump as many responses as possible (for TCP connections)
                        ResponsePacket packet = connection.responseQueue.remove();
                        byte[] payload = packet.getPayload();

                        int dataLen = payload.length + Short.BYTES + 5;

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
                        // PacketID (varies)
                        writeBuffer.put(packet.responseID);
                        // Source connection
                        // 0xFFFF/-1 means arbiter origin / command response
                        writeBuffer.putShort(connection.getConnectionID());
                        // Response data
                        writeBuffer.put(packet.responseData);

                        // If the queue will be empty, write out the remaining packets
                        //if (connection.responseQueue.isEmpty())
                        //{
                        writeBuffer.flip();
                        cmdConnection.channel.write(writeBuffer);
                        //}
                    }

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

                            // Send out the remote read packet
                            // Length
                            temp.putShort((short) dataLen);
                            // Sequence
                            temp.putShort((short) packet.sequence);
                            // PacketID ('R')
                            temp.put(Constants.ARB_PACKET_READ);
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
                        writeBuffer.put(Constants.ARB_PACKET_READ);
                        // Payload
                        writeBuffer.put(payload);

                        // If the queue will be empty, write out the remaining
                        // packets
                        //if (connection.writeQueue.isEmpty())
                        //{
                            writeBuffer.flip();
                            connection.channel.write(writeBuffer);
                        //}
                    }
                } catch (IOException e)
                {
                    // Exception occurred, close the connection
                    e.printStackTrace();
                    connection.closeConnection();
                }

                // Check if the connection is dead or closed
                if (connection.isClosed())
                {
                    if (connection.isDead())
                        System.out.println("Connection #" + connection.getConnectionID() + " died, closing");
                    else
                        System.out.println("Connection #" + connection.getConnectionID() + " was closed");

                    // Alert the endpoint of the connection closure
                    if (connection.getConnectionID() != -1)
                    {
                        ResponsePacket closure = new ResponsePacket(0, Constants.ARB_PACKET_ENDCONN, connection.getConnectionID());
                        cmdConnection.enqueueResponse(closure);
                    }

                    // Perform cleanup
                    connection.closeConnection();
                    connection.channel.close();
                    freeID(connection.getConnectionID());
                }
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
            if (channels.select() > 0)
            {
                Set<SelectionKey> keys = channels.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();

                // Go through all of the keys
                while (iterator.hasNext())
                {
                    SelectionKey key = iterator.next();

                    if (key.isAcceptable())
                    {
                        final byte[] readBytes = new byte[2];
                        SocketChannel channel = ((ServerSocketChannel) key.channel()).accept();

                        // Listen for the client magic
                        readBuffer.clear();
                        channel.configureBlocking(true);
                        channel.read(readBuffer);
                        readBuffer.flip();
                        readBuffer.get(readBytes);

                        if (!Arrays.equals(readBytes, CLIENT_MAGIC))
                        {
                            // Refuse the connection & continue
                            channel.close();
                        }
                        else
                        {
                            // Write back the server magic
                            writeBuffer.clear();
                            writeBuffer.put(SERVER_MAGIC);
                            writeBuffer.flip();
                            channel.write(writeBuffer);
                            channel.configureBlocking(false);

                            // Accept the new connection, sending back connID as a response
                            int connID = addConnection(new Connection((short) allocateID(false), channel), channel);
                            ResponsePacket response = new ResponsePacket(0, Constants.ARB_PACKET_NEWCONN, connID);
                            cmdConnection.enqueueResponse(response);
                        }
                    }

                    if (key.isReadable())
                    {
                        // Read packets from the connection
                        SocketChannel channel = (SocketChannel) key.channel();
                        Connection connection = (Connection) key.attachment();

                        try
                        {
                            processPackets(readBuffer, channel, connection, commandQueue);
                        } catch (IOException e)
                        {
                            e.printStackTrace();
                            connection.closeConnection();
                        }
                    }


                    iterator.remove();
                }
            }

            // Update the dead status
            // Update here to allow a chance for the heartbeat to be updated
            // above, in case of a large amount of packets flowing
            activeConnections.values().forEach((connection) -> {
                if (connection.isDead())
                    connection.closeConnection();
            });
        }
    }

    private void startArbiter()
    {
        try
        {
            initArbiter();
            processChannels();
            // TODO: Shutdown remote channels
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

        System.out.println("Is server: " + (ports[1] != -1));

        // Launch the arbiter
        NetArbiter arbiter = new NetArbiter(ports[0], ports[1]);
        arbiter.startArbiter();
    }

}
