package ddb.io.netarbiter;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

import static ddb.io.netarbiter.Constants.*;

/**
 * Network Arbiter for Turing Programs
 * Provides a simple networking interface to work around some of the bugs
 * inside of the Turing Net Module.
 *
 * Communication between the arbiter and the program is done via a TCP/IP
 * connection established in the Turing program.
 *
 * TODO:
 * V Program - Arbiter connection (local machine connection)
 * V Arbiter - Arbiter connection (remote machine connection)
 * - Local Program connection (passthrough / multiple endpoints)
 * V Arbiter command set (controlling the arbiter connection)
 *  V Close arbiter connection (Exit)
 *  V Open remote connection   (Connect)
 *  V Close remote connection  (Disconnect)
 *  V Send & receive data      (Data packets)
 */
public class NetArbiter {

    private static final String ARBI_ID = "arb:";

    private boolean isRunning = true;
    private int endpointPort, listenPort;

    private Queue<Integer> freeConnectionIDs;
    private boolean connectionIdAvailable = false;

    private List<SocketChannel> remoteConnections;
    private Selector remoteChannels;

    private NetArbiter(int endpoint, int listen) {
        this.endpointPort = endpoint;
        this.listenPort = listen;

        remoteConnections = new ArrayList<> ();
        freeConnectionIDs = new PriorityQueue<>();
    }

    // TODO: Convert over to ZBE

    // Decode data
    private int parseInt(ByteBuffer data, int bytes) {
        assert (bytes > 0 && bytes <= 4);

        long num = 0;

        for (int i = 0; i < (bytes * 2); i++) {
            char digit = Character.toLowerCase((char)data.get());
            int value = 0;

            // Make way for the new digit
            num *= 16;

            if (Character.isDigit(digit))
                value = (digit - '0');
            else if (Character.isAlphabetic(digit) && digit >= 'a' && digit <= 'f')
                value = (digit - 'a') + 10;

            num += value;
        }

        return (int)num;
    }

    private String copyString (ByteBuffer data) {
        // String will always be in the form of [length : 1][string_data]
        byte length = (byte)parseInt(data, 1);
        StringBuilder result = new StringBuilder(length);

        for (int i = 0; i < length; i++)
            result.append((char)data.get());

        return result.toString();
    }

    // Encode data
    private String toNetInt (int num, int bytes) {
        assert (bytes > 0 && bytes <= 4);

        final String dictionary = "0123456789abcdef";
        long val = Integer.toUnsignedLong(num);
        StringBuilder output = new StringBuilder();

        // Convert to network string
        for (int i = (bytes * 2) - 1; i >= 0; i --) {
            output.append(dictionary.charAt((num >> (i * 4)) & 0xF));
        }

        return output.toString();
    }

    private void checkAndFetchMoreBytes(SocketChannel channel, ByteBuffer packetData, int amount) throws IOException {
        if (packetData.remaining() >= amount)
            return;

        // Need to read in more bytes
        //System.out.println("Getting more bytes");

        // Read in the rest of the data
        int totalBytes = packetData.remaining();

        packetData.compact();
        while (totalBytes < amount) {
            int bytes = channel.read(packetData);

            if (bytes == 0)
                continue;

            if (bytes == -1) {
                isRunning = false;
                break;
            }

            // Add on to total
            totalBytes += bytes;
        }
        packetData.flip();
    }

    /**
     * Sends over the appropriate packets in order to establish a connection
     *
     * @param remote The remote connection to establish
     * @return true if the connection was successfully established
     */
    private boolean establishConnection(SocketChannel remote) throws IOException {
        ByteBuffer temporary = ByteBuffer.allocate(8);
        temporary.putInt(PCKTID_HEADER | PCKTID_CONNECT_ESTABLISH);

        // Send connection establish
        temporary.flip();
        remote.write(temporary);

        // Listen for the response
        temporary.clear();
        int length = remote.read(temporary);
        temporary.flip();

        if(length != 4) {
            // Less than 4 bytes were sent or more data than requested was sent
            System.out.println("Error: Wrong number of bytes sent (was " + length + ")");

            remote.close();
            return false;
        }

        int response = temporary.getInt();

        if (response != (PCKTID_HEADER | PCKTID_ACK)) {
            // Proper response wasn't given
            System.out.println("Error: Wrong response received (was " + (response & ~0xFF) + ", " + (response & 0xFF) + ")");

            remote.close();
            return false;
        }

        return true;
    }

    /**
     * Sends a response to the given endpoint
     *
     * @param endpoint The endpoint to send the response to
     * @param type The type of response to send
     * @param parameter The parameter to send back
     * @throws IOException
     */
    private void sendResponse (SocketChannel endpoint, byte type, int parameter) throws IOException {
        ByteBuffer response = ByteBuffer.allocate(9);
        response.clear();

        response.put(type);
        response.put(toNetInt(parameter, 2).getBytes());

        response.flip();
        endpoint.write(response);
    }

    /**
     * Checks the given id and sends the appropriate response
     *
     * @param endpoint The endpoint to send the response to
     * @param connID The connection id to verify
     * @return true if the id is valid
     * @throws IOException
     */
    private boolean checkId (SocketChannel endpoint, int connID) throws IOException {
        if (connID < 0 || connID >= remoteConnections.size()) {
            // Invalid connection ID
            System.out.println("Error: Invalid connection id (was " + connID + ")");
            sendResponse(endpoint, ARB_REP_ERROR, ARB_ERR_INVALID_ARG);
            return false;
        }

        return true;
    }


    private void doConnect(SocketChannel endpoint, ByteBuffer packetData) throws IOException {
        // Connect to remote
        // Format: [port : 2][address : lstring]

        String host;
        int hostLen;
        int port;

        // Get basic information
        // Port + Host Length
        checkAndFetchMoreBytes(endpoint, packetData, 4 + 2);

        port = parseInt(packetData, 2);
        hostLen = parseInt(packetData, 1);

        // Host name
        checkAndFetchMoreBytes(endpoint, packetData, hostLen);

        // Fetch host name
        StringBuilder result = new StringBuilder(hostLen);
        for (int i = 0; i < hostLen; i++)
            result.append((char)packetData.get());
        host = result.toString();


        System.out.println("Connecting to remote arbiter at " + host + ":" + port);
        int connID;

        try {
            System.out.println("Connecting to " + host + ":" + port);

            // Try connecting to the remote arbiter
            SocketChannel remoteSocket = SocketChannel.open(new InetSocketAddress(host, port));

            // Establish the connection
            if (!establishConnection (remoteSocket)) {
                System.out.println("Error: Connection Failed");
                sendResponse(endpoint, ARB_REP_ERROR, ARB_ERR_CONNECTION_FAILED);
            }

            if (connectionIdAvailable) {
                // Use a recently-used slot
                connID = freeConnectionIDs.poll();
                remoteConnections.set(connID, remoteSocket);
                connectionIdAvailable = !freeConnectionIDs.isEmpty();
            } else {
                // Add connection to the list of remotes
                connID = remoteConnections.size();
                remoteConnections.add(remoteSocket);
            }

            // Add Socket to the list of remote sockets
            remoteSocket.configureBlocking(false);
            remoteSocket.register(remoteChannels, SelectionKey.OP_READ, connID);

            // Send back response
            System.out.println("Connection Successful");
            sendResponse (endpoint, ARB_REP_CMD_SUCCESSFUL, connID);
        } catch (ConnectException e) {
            // Handle a connection failure
            System.out.println("Error: Connection Failed");
            sendResponse(endpoint, ARB_REP_ERROR, ARB_ERR_CONNECTION_FAILED);
        } catch (UnresolvedAddressException e) {
            // Handle a bad host address
            System.out.println("Error: Bad host address (was " + host + ")");
            sendResponse(endpoint, ARB_REP_ERROR, ARB_ERR_INVALID_ARG);
        } catch (IOException e) {
            // Unknown case
            e.printStackTrace();
        }
    }

    private void doDisconnect(SocketChannel endpoint, ByteBuffer packetData) throws IOException {
        // Disconnect from remote
        // Format: [connID : 2]
        int connID;

        // Connection ID
        checkAndFetchMoreBytes(endpoint, packetData, 4);

        connID = parseInt(packetData, 2);

        if (!checkId(endpoint, connID))
            return;

        System.out.println("Disconnecting from remote arbiter connection #" + connID);

        // Send the disconnect notify and close up shop
        SocketChannel connection = remoteConnections.get(connID);
        ByteBuffer message = ByteBuffer.allocate(4);

        // Construct the packet & send it
        message.putInt(PCKTID_HEADER | PCKTID_DISCONNECT_NOTIFY);
        message.flip();
        connection.write(message);
        connection.close();

        // Add the connection id to the available list of ids
        remoteConnections.set (connID, null);
        connectionIdAvailable = true;
        freeConnectionIDs.add(connID);

        // Send back a good response
        sendResponse(endpoint, ARB_REP_CMD_SUCCESSFUL, 0);
    }

    private void doSend (SocketChannel endpoint, ByteBuffer packetData) throws IOException {
        // Sending data over to a remote arbiter
        // Format: [connID : 2][size : 2][payload] (connID taken care of)
        int connID;

        // For connID + payloadSize
        checkAndFetchMoreBytes (endpoint, packetData, 8);

        // Parse the connection ID
        connID = parseInt(packetData, 2);

        // Check the id
        if (!checkId (endpoint, connID))
            return;

        int payloadSize = parseInt(packetData, 2);

        // For the payload
        checkAndFetchMoreBytes (endpoint, packetData, payloadSize);

        // Copy payload data into another byte buffer
        ByteBuffer payload = ByteBuffer.allocate(payloadSize + Short.BYTES);

        // Build the remote payload
        payload.clear();
        payload.putShort((short) payloadSize);
        payload.put(packetData.array(), packetData.position(), payloadSize);
        payload.flip();

        // Skip over the payload data
        packetData.position(packetData.position() + payloadSize);

        SocketChannel connection = remoteConnections.get(connID);
        connection.write(payload);

        // Send back status
        sendResponse(endpoint, ARB_REP_CMD_SUCCESSFUL, 0);
    }

    /**
     * Parses the command data and sends an appropriate response
     *
     * @param endpoint The endpoint to send data to
     * @param packetData The packet data to parse.
     */
    private void parseCommand(SocketChannel endpoint, ByteBuffer packetData) throws IOException {
        // Parsing an arbiter command
        byte commandID = packetData.get();

        switch (commandID) {
            case ARB_CMD_EXIT:
                // Exit arbiter
                System.out.println("Closing connection with endpoint");
                isRunning = false;
                break;
            case ARB_CMD_CONNECT:
                doConnect(endpoint, packetData);
                break;
            case ARB_CMD_DISCONNECT:
                doDisconnect(endpoint, packetData);
                break;
            case ARB_CMD_SEND:
                doSend(endpoint, packetData);
                break;
            default:
                System.out.println("Invalid command ID received: " + Integer.toHexString(commandID));
            }

    }

    /**
     * Begins the endpoint-side arbiter
     * - Waits for a connection from the program
     * - Parses the given commands
     * - Reads in data from remote connections
     */
    private void startEndpoint() {
        // Setup the endpoint listener & wait for an endpoint
        SocketChannel endpoint = null;

        try {
            ServerSocketChannel endpointListener;
            endpointListener = ServerSocketChannel.open();
            endpointListener.bind(new InetSocketAddress("localhost", endpointPort), 0);
            System.out.println("Waiting for an endpoint to connect");

            endpoint = endpointListener.accept();
            // Configure the endpoint socket to be nonblocking
            endpoint.configureBlocking(false);

            // Endpoint listener no longer needed
            endpointListener.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Done to satisfy any warnings
        if (endpoint == null)
            return;

        // TODO: Setup Remote Connection Listener
        try {
            remoteChannels = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // State: Endpoint connected, Endpoint Listener closed
        ByteBuffer packetData = ByteBuffer.allocate(1024);

        System.out.println("Connection established with endpoint");

        try {
            while (isRunning) {
                // Check for commands
                packetData.clear();
                int length = endpoint.read(packetData);
                packetData.flip();

                if (length == -1)
                    break;

                while (packetData.hasRemaining())
                    parseCommand (endpoint, packetData);

                // Check for data from remote sources
                int numReady = remoteChannels.selectNow();

                if (numReady > 0) {
                    Set<SelectionKey> keys = remoteChannels.selectedKeys();
                    Iterator<SelectionKey> iterator = keys.iterator();

                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();

                        if (key.isReadable()) {
                            // Readable channel has been given, send to the endpoint
                            SocketChannel remote = (SocketChannel) key.channel();

                            packetData.clear();
                            remote.read(packetData);
                            packetData.flip();

                            while (packetData.hasRemaining()) {
                                // Get payload size
                                int payloadSize = Short.toUnsignedInt(packetData.getShort());

                                checkAndFetchMoreBytes(remote, packetData, payloadSize);

                                // Send the received packet to the endpoint
                                // 1 byte for the resp_id, 4 bytes for connection id, 4 bytes for payload size (9 total)
                                ByteBuffer payload = ByteBuffer.allocate(payloadSize + 9);
                                payload.put(ARB_REP_DATA_RECEIVED);
                                payload.put(toNetInt( (int)key.attachment(), 2).getBytes());
                                payload.put(toNetInt( payloadSize, 2).getBytes());
                                payload.put(packetData.array(), packetData.position(), payloadSize);

                                payload.flip();
                                endpoint.write(payload);

                                packetData.position(packetData.position() + payloadSize);
                            }
                        }

                        iterator.remove();
                    }
                }
            }

            // Gracefully close connection with endpoint
            remoteChannels.close();
            endpoint.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // State: Endpoint disconnected
    }

    /**
     * Begins the simple server listener
     * To be merged into the "startEndpoint" method
     */
    private void startListener() {
        System.out.println("Waiting for a connection from the client");

        // Setup the server channel
        ServerSocketChannel listener = null;

        try {
            listener = ServerSocketChannel.open();
            listener.bind(new InetSocketAddress(listenPort));
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (listener == null)
            return;

        System.out.println("Waiting for connections");

        try {
            SocketChannel arbiter = listener.accept();
            arbiter.configureBlocking(false);

            ByteBuffer packet = ByteBuffer.allocate(1024);
            boolean isRunning = true;

            System.out.println("Connection accepted");

            //int sequence = 0;
            while (isRunning) {
                // Read in data
                packet.clear();
                int length = arbiter.read(packet);
                packet.flip();

                if (length == -1) {
                    System.out.println("End of channel");
                    arbiter.close();
                    break;
                }

                if (length == 0)
                    continue;

                while (packet.hasRemaining()) {
                    // Get the packet id
                    packet.mark();

                    int packetID = packet.getInt();

                    if ((packetID & PCKTID_HEADER) == PCKTID_HEADER) {
                        switch (packetID & 0xFF) {
                            case PCKTID_CONNECT_ESTABLISH:
                                System.out.println("Connection requested from client");

                                // Send an ack for the connection request
                                packet.clear();
                                packet.putInt(PCKTID_HEADER | PCKTID_ACK);
                                packet.flip();

                                arbiter.write(packet);
                                break;
                            case PCKTID_DISCONNECT_NOTIFY:
                                System.out.println("Disconnect from client");
                                isRunning = false;
                                break;
                            default:
                                System.out.println("Invalid interconnect id: " + Integer.toHexString(packetID & 0xFF));
                                break;
                        }
                    } else {
                        //System.out.println("Data Received:");

                        // Print out data
                        packet.reset();

                        int payloadSize = Short.toUnsignedInt(packet.getShort());

                        checkAndFetchMoreBytes(arbiter, packet, payloadSize);
                        /*if (payloadSize > packet.remaining()) {
                            //System.out.println("Getting more bytes");

                            // Read in the rest of the data
                            int totalBytes = packet.remaining();

                            packet.compact();
                            while (totalBytes < payloadSize) {
                                int bytes = arbiter.read(packet);

                                if (bytes == 0)
                                    continue;

                                if (bytes == -1) {
                                    isRunning = false;
                                    break;
                                }

                                // Add on to total
                                totalBytes += bytes;
                            }
                            packet.flip();


                        }*/

                        if (!isRunning)
                            break;

                        /*System.out.print (++sequence);
                        for (int i = 0; i < payloadSize; i++)
                            System.out.print((char) packet.get());
                        System.out.println();
                        packet.reset()*/

                        // Send the data back
                        ByteBuffer echo = ByteBuffer.allocate(payloadSize + Short.BYTES);
                        echo.putShort ((short) payloadSize);
                        echo.put(packet.array(), packet.position(), payloadSize);
                        echo.flip();
                        arbiter.write(echo);

                        // Move to the next data bit
                        packet.position(packet.position() + payloadSize);
                    }
                }
            }
        } catch (IOException e) {
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

        if (ports[1] != -1)
            arbiter.startListener();
        else
            arbiter.startEndpoint();
    }

}
