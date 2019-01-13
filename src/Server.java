import org.xbill.DNS.ResolverConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.Set;


class Attachment {
    static final int SIZE = 8192;
    ByteBuffer readBuf, writeBuf;
    SelectionKey peer = null;
    int debug;
    short port;

    Attachment() {
    }

    Attachment(SelectionKey key) {
        peer = key;
        readBuf = ByteBuffer.allocate(SIZE);
    }
}

class Server {
    int debug = 0;
    private short port;
    private Boolean init = false;
    private Selector selector;
    private SelectionKey DNSKey;
    private static final byte[] OK = new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0};

    Server(short port) {
        this.port = port;
        String dnsServers[] = ResolverConfig.getCurrentConfig().servers();
        try {
            ServerSocketChannel local = ServerSocketChannel.open();
            DatagramChannel DNSResolvingChannel = DatagramChannel.open();
            Selector selector = Selector.open();
            this.selector = selector;
            local.bind(new InetSocketAddress("localhost", port));
            local.configureBlocking(false);
            local.register(selector, SelectionKey.OP_ACCEPT);
            InetSocketAddress dnsServerAddress = new InetSocketAddress(InetAddress.getByName(dnsServers[0]), 53);
            DNSResolvingChannel.connect(dnsServerAddress);
            DNSResolvingChannel.configureBlocking(false);
            DNSKey = DNSResolvingChannel.register(selector, SelectionKey.OP_READ);
            DNSKey.attach(new HashMap<String, SelectionKey>());
            init = true;
        } catch (IOException e) {
            System.err.println("Initialization error");
        }
    }

    private void print(String s) {
        boolean debug = true;
        if (debug)
            System.out.println(s);
    }

    void start() {
        if (!init)
            return;
        try {
            while (true) {
                selector.select();
                Set<SelectionKey> keys = selector.selectedKeys();
                System.out.println(">>Select start");
                for (SelectionKey key : keys) {
                    if (key.isValid()) {
                        if (key.isWritable()) {
                            print("WRITE " + ((Attachment) key.attachment()).debug);
                            write(key);
                        } else if (key.isConnectable()) {
                            print("CONNECT " + ((Attachment) key.attachment()).debug);
                            connect(key);
                        } else if (key.isReadable()) {
                            if (key.attachment() instanceof Attachment)
                                print("READ " + ((Attachment) key.attachment()).debug);
                            else
                                print("READ DNS");
                            read(key);
                        } else if (key.isAcceptable()) {
                            print("ACCEPT");
                            accept(key);
                        }
                    }
                }
                keys.clear();
                System.out.println(">>Select finish");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
        }
    }

    private void accept(SelectionKey key) throws IOException {
        /*if (debug > 1) {
            key.cancel();
            return;
        }*/
        SocketChannel inChannel = ((ServerSocketChannel) key.channel()).accept();
        SocketChannel outChannel = SocketChannel.open();
        inChannel.configureBlocking(false);
        outChannel.configureBlocking(false);
        SelectionKey inKey = inChannel.register(selector, SelectionKey.OP_READ);
        SelectionKey outKey = outChannel.register(selector, 0);
        inKey.attach(new Attachment(outKey));
        outKey.attach(new Attachment(null));

        ((Attachment) inKey.attachment()).debug = debug++;
        ((Attachment) outKey.attachment()).debug = debug++;
        System.out.println("    Accepted");
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Attachment attachment = (Attachment) key.attachment();
        ByteBuffer buf = attachment.writeBuf;
        int count = buf.position();
        System.out.println("    Write " + count + " bytes");
        if (count == 0)
            return;
        buf.flip();
        int written = channel.write(attachment.writeBuf);
        if (written < count) {
            buf.position(count - written);
            System.out.println("    Written " + written);
        } else {
            buf.clear();
            if (attachment.peer.attachment() == null) {
                key.cancel();
                key.channel().close();
            } else {
                key.interestOps(SelectionKey.OP_READ);
                attachment.peer.interestOps(SelectionKey.OP_READ);
            }
            System.out.println("    Written all");
        }
    }

    private void connect(SelectionKey key) throws IOException {

        SocketChannel keyChannel = ((SocketChannel) key.channel());
        if (!keyChannel.isConnected()) {
            System.out.println("    Not connected yet");
            if (keyChannel.finishConnect()) {
                key.interestOps(SelectionKey.OP_WRITE);
                System.out.println("    Connected from now on");
            }

        }

    }

    private void read(SelectionKey key) throws IOException {
        if ((Channel) key.channel() == DNSKey.channel())
            new Reader(DNSKey, port).readAndReplyDNS();
        else
            new Reader(key, DNSKey, port).read();
    }

}
