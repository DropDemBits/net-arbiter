package ddb.io.netarbiter;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import static ddb.io.netarbiter.Constants.*;

public class TestArbiter {

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

        // Setup a server
        try (ServerSocket socket = new ServerSocket(port);
             Socket client = socket.accept();
             BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream());
             BufferedInputStream in = new BufferedInputStream(client.getInputStream())) {
            System.out.println("Stream established");

            // Wait for a client connection
            byte[] data = new byte[1500];
            int length;

            while ((length = in.read(data, 0, data.length)) != 0) {
                // Read the packet ID
                int packetID = StreamSerializer.getInt(data, 0);

                if ((packetID & PCKTID_HEADER) == PCKTID_HEADER) {
                    switch (packetID & 0xFF) {
                        case PCKTID_CONNECT_ESTABLISH:
                            System.out.println("Connection requested from client");

                            // Send an ack for the connection request
                            byte[] temp = new byte[4];
                            StreamSerializer.appendInt(temp, 0, PCKTID_HEADER | PCKTID_ACK);

                            out.write(temp);
                            out.flush();
                            break;
                        case PCKTID_DISCONNECT_NOTIFY:
                            System.out.println("Disconnection from client");
                            break;
                        default:
                            System.out.println("Invalid interconnect id: " + Integer.toHexString(packetID & 0xFF));
                            break;
                    }
                } else {
                    System.out.println("Data Received");
                    System.out.println(Integer.toHexString(data[0]));
                    System.out.println(Integer.toHexString(data[1]));
                    System.out.println(Integer.toHexString(data[2]));
                    System.out.println(Integer.toHexString(data[3]));

                    for (int i = 0; i < length; i++)
                        System.out.print((char) data[i]);

                    System.out.println();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
