package ddb.io.netarbiter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static ddb.io.netarbiter.Constants.*;

public class RemoteConnection {

    private SocketChannel channel;

    RemoteConnection (SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * Sends over the appropriate packets in order to establish a connection
     */
    public boolean establish() throws IOException {
        // Connection is already closed, no need to do anything
        /*if (!channel.isConnected()) {
            System.out.println("Warning: An establish-after-close was attempted");
            return true;
        }*/

        ByteBuffer temporary = ByteBuffer.allocate(8);
        temporary.putInt(PCKTID_HEADER | PCKTID_CONNECT_ESTABLISH);

        // Send connection establish
        temporary.flip();
        channel.write(temporary);

        // Listen for the response
        temporary.clear();
        int length = channel.read(temporary);
        temporary.flip();

        if(length != 4) {
            // Less than 4 bytes were sent or more data than requested was sent
            System.out.println("Error: Wrong number of bytes sent (was " + length + ")");

            channel.close();
            return false;
        }

        int response = temporary.getInt();

        if ((response & PCKTID_HEADER) != PCKTID_HEADER) {
            // Got garbage data, ignore
            System.out.println("Error: Valid packet not received (was " + response + ")");

            channel.close();
            return false;
        }
        else if ((response & 0xFF) != PCKTID_ACK) {
            // Proper response wasn't given
            System.out.println("Error: Wrong response received (was " + (response & 0xFF) + ")");

            channel.close();
            return false;
        }

        return true;
    }

    /**
     * Sends the disconnect response and cleans up resources
     * @throws IOException
     */
    public void disconnect () throws IOException {
        // No need to send the notify onto a dead connection
        if (!channel.isConnected())
            return;

        ByteBuffer temporary = ByteBuffer.allocate(4);

        // Construct the packet
        temporary.putInt(PCKTID_HEADER | PCKTID_DISCONNECT_NOTIFY);
        temporary.flip();

        // Send the disconnect notify
        channel.write(temporary);

        // Close up shop
        channel.close();
    }

    /**
     * Puts the data given onto the network
     * The given buffer should already be flipped for reading
     *
     * @param data The data to send over
     * @throws IOException
     */
    public void write(ByteBuffer data) throws IOException {
        channel.write(data);
    }

    /**
     * Receives data from the network
     * The given buffer should already be cleared for writting
     *
     * @param data The destination for the received data
     * @throws IOException
     */
    public void read(ByteBuffer data) throws IOException {
        channel.read(data);
    }
}
