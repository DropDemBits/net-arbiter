package ddb.io.netarbiter;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static ddb.io.netarbiter.Constants.*;

public class TestArbiter {

    private static String toNetInt (int num, int bytes) {
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

    public static void main (String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: testArbiter --serverPort=[port]");
            return;
        }

        // Parse the port argument
        String[] arg = args[0].split("=");

        if (!arg[0].equals("--serverPort")) {
            System.out.println("Unknown argument " + arg[0]);
        }

        int port = Integer.parseInt(arg[1]);

        System.out.println("Waiting for a connection from the client");

        // Setup the server channel
        ServerSocketChannel listener = null;

        try {
            listener = ServerSocketChannel.open();
            listener.bind(new InetSocketAddress(port));
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
                        System.out.println("Data Received:");

                        // Print out data
                        packet.reset();

                        short payloadSize = packet.getShort();

                        if (payloadSize > packet.capacity()) {
                            System.out.println("Warning: Payload too big!");
                            continue;
                        } else if (payloadSize > packet.limit()) {
                            System.out.println("Getting more bytes");

                            // Read in the rest of the data
                            int totalBytes = packet.remaining();

                            while (totalBytes < payloadSize) {
                                packet.compact();
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

                            if (!isRunning)
                                break;
                        }

                        for (int i = 0; i < payloadSize; i++)
                            System.out.print((char) packet.get());
                        System.out.println();

                        // Send the data back
                        packet.reset();
                        arbiter.write(packet);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
