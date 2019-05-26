package ddb.io.netarbiter;

import java.io.*;
import java.net.Socket;
import java.util.stream.Stream;

import static ddb.io.netarbiter.PacketIDs.*;

public class RemoteConnection {

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    RemoteConnection (Socket socket) {
        this.socket = socket;

        try {
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = new BufferedOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends over the appropriate packets in order to establish a connection
     */
    public boolean establish() throws IOException {
        byte[] data = new byte[4];
        StreamSerializer.appendInt(data, 0, PCKTID_HEADER | PCKTID_CONNECT_ESTABLISH);
        out.write(data, 0, data.length);
        out.flush();

        // Listen for the response
        int length = in.read(data, 0, data.length);
        int response = StreamSerializer.getInt(data, 0);

        if ((response & PCKTID_HEADER) != PCKTID_HEADER) {
            // Got garbage data, ignore
            return false;
        }
        else if ((response & 0xFF) != PCKTID_ACK) {
            // Proper response wasn't given
            return false;
        }

        return true;
    }

    /**
     * Sends the disconnect response and cleans up resources
     * @throws IOException
     */
    public void disconnect () throws IOException {
        byte[] data = new byte[4];
        StreamSerializer.appendInt(data, 0, PCKTID_HEADER | PCKTID_DISCONNECT_NOTIFY);
        out.write(data);

        out.flush();

        try {
            Thread.sleep(500);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        out.close();
        in.close();
        socket.shutdownInput();
        socket.shutdownOutput();
        socket.close();
    }

    public void write(byte[] packet_buffer, int offset, int size) throws IOException {
        out.write(packet_buffer, offset, size);
        out.flush();
    }
}
