package ddb.io.netarbiter;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

import static ddb.io.netarbiter.Constants.*;

/**
 * Network Arbiter for Turing Programs
 * Provides a versatile networking interface than the Turing Net Module.
 *
 * Communication between the arbiter and the program is done via a TCP/IP
 * connection established in the Turing program.
 *
 * TODO:
 * - Program - Arbiter connection (local machine connection)
 * - Arbiter - Arbiter connection (remote machine connection)
 * - Local Program connection (passthrough)
 * - Arbiter command set (controlling the arbiter connection)
 *  - Close arbiter connection
 *  - Wait for remote connection
 *  - Open remote connection
 *  - Close remote connection
 *  - BIO send & receive
 *  - AIO send & receive
 * - Handle remote attacks?
 *  - Prevent remote connection from sending "Close arbiter connection"
 */
public class NetArbiter {

    private static final String ARBI_ID = "arb:";

    private boolean isRunning = true;
    private int endpointPort, listenPort;

    private Queue<Integer> outboundFreeSlots;
    private boolean outboundHasFreeSlot = false;

    private List<RemoteConnection> outboundConnections;
    private List<RemoteConnection> inboundConnections;

    private NetArbiter(int endpoint, int listen) {
        this.endpointPort = endpoint;
        this.listenPort = listen;

        outboundConnections = new ArrayList<> ();
        inboundConnections = new ArrayList<> ();

        outboundFreeSlots = new PriorityQueue<>();
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


    private int tryConnectingTo(String address, int remotePort) {
        System.out.println("Connecting to " + address + ":" + remotePort);

        try {
            // Try connecting to the remote arbiter
            SocketChannel remoteSocket = SocketChannel.open(new InetSocketAddress(address, remotePort));
            RemoteConnection connection = new RemoteConnection(remoteSocket);

            // Establish the connection
            // Return -1 on failure
            if (!connection.establish ())
                return -1;

            int connID;

            if (outboundHasFreeSlot && !outboundFreeSlots.isEmpty()) {
                // Use a recently-used slot
                connID = outboundFreeSlots.poll();
                outboundConnections.set(connID, connection);
            } else {
                // Add connection to the list of remotes
                connID = outboundConnections.size();
                outboundConnections.add(connection);
            }

            return connID;
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
    }

    /**
     * Parses the command data and sends an appropriate response
     *
     * @param endpoint The endpoint to send data to
     * @param packetData The packet data to parse.
     * @param length The original read length
     */
    private void parseCommand(SocketChannel endpoint, ByteBuffer packetData, int length) throws IOException {
        ByteBuffer response = ByteBuffer.allocate(16);
        int id = packetData.getInt();

        if (id == ARB_HEADER) {
            // Parsing an arbiter command
            byte commandID = packetData.get();

            switch (commandID) {
                case ARB_CMD_EXIT:
                    // Exit arbiter
                    System.out.println("Closing connection with endpoint");
                    isRunning = false;
                    break;
                case ARB_CMD_CONNECT: {
                    // Connect to remote
                    String host;
                    int port;

                    // Format: [port : 2][address : lstring]
                    port = parseInt(packetData, 2);
                    host = copyString(packetData);

                    System.out.println("Connecting to remote arbiter at " + host + ":" + port);
                    int connID = tryConnectingTo(host, port);

                    // Build response
                    response.clear();

                    if (connID != -1) {
                        System.out.println("Connection Successful");

                        response.putInt(ARB_HEADER);
                        response.put(ARB_REP_ESTABLISH);
                        response.put(toNetInt(0, 2).getBytes());
                    } else {
                        System.out.println("Error: Connection Failed");

                        response.putInt(ARB_HEADER);
                        response.put(ARB_REP_ERROR);
                        response.put(toNetInt(ARB_ERR_CONNECTION_FAILED, 2).getBytes());
                    }

                    response.flip();
                    endpoint.write(response);
                    break;
                }
                case ARB_CMD_DISCONNECT: {
                    // Disconnect from remote
                    int connID;

                    // Format: [connID : 2]
                    connID = parseInt(packetData, 2);

                    if (connID < 0 || connID >= outboundConnections.size()) {
                        // Invalid connection ID
                        System.out.println("Error: Invalid connection id for disconnect (was " + connID + ")");

                        response.clear();

                        response.putInt(ARB_HEADER);
                        response.put(ARB_REP_ERROR);
                        response.put(toNetInt(ARB_ERR_INVALID_ID, 2).getBytes());

                        response.flip();
                        endpoint.write(response);

                        break;
                    }

                    System.out.println("Disconnecting from remote arbiter connection #" + connID);

                    RemoteConnection connection = outboundConnections.get(connID);
                    connection.disconnect();
                    outboundConnections.set (connID, null);

                    outboundHasFreeSlot = true;
                    outboundFreeSlots.add(connID);

                    break;
                }
                default:
                    System.out.println("Invalid command ID received: " + Integer.toHexString(commandID));
            }
        } else {
            // Sending data over to a remote arbiter
            int connID;
            packetData.rewind();

            // Reparse the connection ID
            connID = parseInt(packetData, 2);

            System.out.println("Sending data to remote arbiter #" + connID);

            // Check the id
            if (connID < 0 || connID >= outboundConnections.size()) {
                // Invalid connection ID
                System.out.println("Error: Invalid connection id for send (was " + connID + ")");

                response.clear();

                response.putInt(ARB_HEADER);
                response.put(ARB_REP_ERROR);
                response.put(toNetInt(ARB_ERR_INVALID_ID, 2).getBytes());

                response.flip();
                endpoint.write(response);

                return;
            }

            // Format: [connID : 2][size : 2][payload] (connID taken care of)
            short payloadSize = (short) parseInt(packetData, 2);

            // Copy payload data into another byte buffer
            ByteBuffer payload = ByteBuffer.allocate(payloadSize + Short.BYTES);

            // Build the remote payload
            payload.clear();
            System.out.println(payload.toString());

            payload.putShort(payloadSize);
            payload.put(packetData.array(), packetData.position(), payloadSize);
            payload.flip();

            packetData.position(packetData.arrayOffset() + payloadSize);

            RemoteConnection connection = outboundConnections.get(connID);
            connection.write(payload);

            System.out.println("Successfully sent payload over");

            // Send back status
            response.clear();

            response.putInt(ARB_HEADER);
            response.put(ARB_REP_SUCCESS_SENT);

            response.flip();
            endpoint.write(response);
        }
    }

    /**
     * Begins the endpoint-side arbiter
     * - Waits for a connection from the program
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

        // State: Endpoint connected, Endpoint Listener closed
        ByteBuffer packetData = ByteBuffer.allocate(1024);

        System.out.println("Connection established with endpoint");

        try {
            while (isRunning) {
                // Check for commands
                packetData.clear();
                int length = endpoint.read(packetData);
                packetData.flip();

                if (length == -1) {
                    break;
                }

                if (length > 0)
                    parseCommand (endpoint, packetData, length);

                // Check for data from remote sources
            }

            // Gracefully close connection with endpoint
            endpoint.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // State: Endpoint disconnected
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
        arbiter.startEndpoint();
    }

}
