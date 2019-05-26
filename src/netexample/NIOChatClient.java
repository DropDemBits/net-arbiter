package netexample;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class NIOChatClient {

    public static void main(String[] args) {
        try {
            ByteBuffer sendRecv = ByteBuffer.allocate(256);

            SocketAddress sockAddr = new InetSocketAddress("localhost", 8888);
            SocketChannel channel = SocketChannel.open(sockAddr);

            if(!channel.finishConnect()) {
                System.out.println("Failed to connect to chat server");
                return;
            }

            String testString = "hallo";
            sendRecv.put(testString.getBytes());
            sendRecv.flip();
            channel.write(sendRecv);

            sendRecv.clear();
            channel.read(sendRecv);
            sendRecv.flip();

            while (sendRecv.hasRemaining())
                System.out.println((char) sendRecv.get());

            channel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
