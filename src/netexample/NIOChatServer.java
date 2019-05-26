package netexample;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class NIOChatServer {

    public static void main(String[] args) {
        try {
            ByteBuffer sendRecv = ByteBuffer.allocate(256);
            int port = 8888;

            // Setup the server socket channel
            SocketAddress sockAddr = new InetSocketAddress(port);
            ServerSocketChannel channel = ServerSocketChannel.open();
            channel.bind(sockAddr);

            // Wait until the client connects
            SocketChannel client = channel.accept();

            // Read the client data
            sendRecv.clear();
            client.read(sendRecv);
            sendRecv.flip();

            while (sendRecv.hasRemaining())
                System.out.println((char) sendRecv.get());

            // Send over a response
            String response = "holla";

            sendRecv.clear();
            sendRecv.put(response.getBytes());
            sendRecv.flip();
            client.write(sendRecv);

            client.close();
            channel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
