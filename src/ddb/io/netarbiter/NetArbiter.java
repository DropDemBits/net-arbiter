package ddb.io.netarbiter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;

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

    private int connectionPort;
    //private DatagramSocket remoteSocket;

    private NetArbiter(int connectionPort) {
        this.connectionPort = connectionPort;
    }

    // Decode data
    private int parseInt(char[] data, int offset, int bytes) {
        assert (bytes > 0 && bytes <= 4);

        // Don't parse outside the buffer
        if((offset + (bytes * 2) - 1) >= data.length)
            return Integer.MIN_VALUE;

        long num = 0;

        for (int i = 0; i < (bytes * 2); i++) {
            char cval = Character.toLowerCase(data[i + offset]);
            int digit = 0;

            // Make way for the new digit
            num *= 16;

            if (Character.isDigit(cval))
                digit = (cval - '0');
            else if (Character.isAlphabetic(cval) && cval >= 'a' && cval <= 'f')
                digit = (cval - 'a') + 10;

            num += digit;
        }

        return (int)num;
    }

    private String copyString (char[] data, int offset, int length) {
        // Check if in the bounds of the data buffer
        if (offset + length >= data.length)
            return "";

        // Get the targeted string segment
        return String.valueOf(data, offset, length);
    }

    // Encode data
    private String toNetInt (int num, int bytes) {
        assert (bytes > 0 && bytes <= 4);

        final String dictionary = "0123456789abcdef";
        long val = Integer.toUnsignedLong(num);
        StringBuilder output = new StringBuilder();

        // Cap large values
        /*if (val >= ((long)1 << (bytes * 2)) - 1) {
            switch(bytes) {
                case 1: return "FF";
                case 2: return "FFFF";
                case 3: return "FFFFFF";
                case 4: return "FFFFFFFF";
            }
        }*/

        // Convert to network string
        for (int i = (bytes * 2) - 1; i >= 0; i --) {
            output.append(dictionary.charAt((num >> (i * 4)) & 0xF));
        }

        return output.toString();
    }

    /**
     * Begins the arbiter
     * - Waits for a connection from the program
     */
    private void start() {
        try(
                ServerSocket serverSocket = new ServerSocket(connectionPort);
                Socket endpointSocket = serverSocket.accept();
                PrintWriter out = new PrintWriter(endpointSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(endpointSocket.getInputStream()))) {
            System.out.println("Connection Established");

            // Wait for data to send
            /*String inputLine;
            long reportingPeriod = System.currentTimeMillis();
            long numTransfers = 0;
            long totTransfers = 0;
            long reportedTransfers = 0;

            while((inputLine = in.readLine()) != null) {
                //System.out.println("Recv: " + inputLine);

                // Don't send \r as Turing automagically consumes carriage
                // returns
                out.print("AAAA: " + inputLine + '\n');
                out.flush();

                // Calculate number of transfers
                numTransfers += 1;

                if((System.currentTimeMillis() - reportingPeriod) > 1000) {
                    reportingPeriod = System.currentTimeMillis();

                    totTransfers += numTransfers;
                    reportedTransfers ++;

                    System.out.println(numTransfers + " T/S (Avg " + totTransfers / reportedTransfers + " T/S)");
                    numTransfers = 0;
                }
            }*/

            char[] packet_buffer = new char[1500];
            int packet_size = 0;

            while ((packet_size = in.read(packet_buffer, 0, packet_buffer.length)) != 0) {
                if (packet_size == -1)
                    break;

                if (packet_size < 4)
                    continue;

                //System.out.println(new String(packet_buffer));

                int offset = 0;
                boolean isControl = true;

                for (int i = 0; i < ARBI_ID.length(); i++) {
                    if(packet_buffer[i] != ARBI_ID.charAt(i)) {
                        isControl = false;
                        break;
                    }
                }

                if (isControl) {
                    // Received packet is a control one

                    offset += ARBI_ID.length() + 1;

                    // Get Command ID
                    switch (packet_buffer[offset - 1]) {
                        case 'X': {
                            // Exit notification
                            System.out.println("Communication closed");
                            break;
                        }
                        case 'C': {
                            // Remote connection establish

                            // Get port
                            int rport = parseInt(packet_buffer, offset, 2);
                            offset += 4;

                            // Get address
                            int length = parseInt(packet_buffer, offset, 1);
                            String address = copyString(packet_buffer, offset + 2, length);

                            System.out.println("Connecting to " + rport + ":" + address);

                            // Send back the new connection ID
                            String send_data = "";

                            send_data += ARBI_ID;
                            send_data += 'E';
                            send_data += toNetInt(new Random().nextInt() & 0xFFFF, 2);

                            out.print(send_data);
                            out.flush();
                            break;
                        }
                        case 'D': {
                            // Remote connection disconnect
                            int conID = parseInt(packet_buffer, offset, 2);

                            System.out.println("Disconnecting connection #" + conID);
                            break;
                        }
                        case 'L': {
                            // Change remote listening port
                            int port = parseInt(packet_buffer, offset, 2);

                            System.out.println("Listening for remote connections on port " + port);
                            break;
                        }
                        default:
                            // Invalid command id
                            System.out.println("Unknown command id: " + (int)packet_buffer[offset - 1]);
                            break;
                    }
                } else {
                    int connID = parseInt(packet_buffer, offset, 2);
                    offset += 4;

                    System.out.println("Sending over data to #" + connID + ":");
                    System.out.println(String.valueOf(packet_buffer, offset, packet_size - offset));
                }

                // Fill the array again
                Arrays.fill(packet_buffer, (char)0xFF);
            }

            System.out.println("Cleaned up resources");
        } catch (IOException e) {
            System.out.println("Exception when trying to listen to port " + connectionPort);
            System.out.println(e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Gather connection information
        if (args.length < 1) {
            System.out.println("Usage: arbiter [port]");
            return;
        }

        // Acquire connection port
        int connectionPort = Integer.decode(args[0]);

        // Launch the arbiter
        NetArbiter arbiter = new NetArbiter(connectionPort);
        arbiter.start();
    }

}
