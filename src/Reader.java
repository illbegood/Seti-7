import javafx.util.Pair;
import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;


class Reader {
    private SocketChannel inChannel, outChannel;
    private Attachment inAttachment, outAttachment;
    private SelectionKey inKey, outKey;
    private short port;
    private byte[] response;
    private DatagramChannel DNSChannel;
    private HashMap<String, SelectionKey> namesToBeResolved;

    Reader(SelectionKey inKey, SelectionKey DNSKey, short port) {
        this.inKey = inKey;
        inChannel = (SocketChannel) inKey.channel();
        inAttachment = ((Attachment) inKey.attachment());
        outKey = inAttachment.peer;
        outChannel = (SocketChannel) outKey.channel();
        outAttachment = ((Attachment) outKey.attachment());
        this.DNSChannel = (DatagramChannel) DNSKey.channel();
        namesToBeResolved = (HashMap<String, SelectionKey>) DNSKey.attachment();
        this.port = port;
        response = new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte) (this.port >> 8), (byte) (this.port & 0xFF)};
    }

    Reader(SelectionKey DNSKey, short port) {
        this.DNSChannel = (DatagramChannel) DNSKey.channel();
        namesToBeResolved = (HashMap<String, SelectionKey>) DNSKey.attachment();
        this.port = port;
        response = new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte) (this.port >> 8), (byte) (this.port & 0xFF)};
    }

    void read() throws IOException {
        if (outAttachment == null) {
            inKey.cancel();
            inKey.channel().close();
            return;
        }
        try {
            if (inChannel.read(inAttachment.readBuf) == -1) {
                inKey.cancel();
                inKey.channel().close();
                inKey.attach(null);
                return;
            }
        } catch (Exception e) {
            inKey.cancel();
            inKey.channel().close();
            inKey.attach(null);
            return;
        }
        if (inAttachment.readBuf.position() == 0)
            return;
        System.out.println("    READ " + inAttachment.debug + " " + inAttachment.readBuf.position() + " bytes");
        if (outAttachment.peer == null) {
            inAttachment.writeBuf = inAttachment.readBuf;
            readHeadersAndReply();
        } else {
            outAttachment.writeBuf = inAttachment.readBuf;
            if (outChannel.isConnected())
                outKey.interestOps(SelectionKey.OP_WRITE);
            else
                outKey.interestOps(SelectionKey.OP_CONNECT);
            inKey.interestOps(0);
            System.out.println("    READ Done");
        }
    }

    private void readHeadersAndReply() throws IOException {
        byte[] buf = inAttachment.readBuf.array();
        if (buf[0] != 5)//version
            return;
        if (buf[1] == inAttachment.readBuf.position() - 2) {//auth
            inAttachment.writeBuf.clear();//aka readBuf
            inAttachment.writeBuf.put(new byte[]{5, 0});
            inKey.interestOps(SelectionKey.OP_WRITE);
        } else {//request
            if (buf[1] != 1)//TCP
                return;
            if (buf[3] == 1)
                reply(buf);
            else if (buf[3] == 3)
                writeDNS(buf);
        }
    }

    private void reply(byte buf[]) throws IOException {
        byte[] address = new byte[4];
        short port = ByteBuffer.wrap(new byte[]{buf[8], buf[9]}).getShort();
        System.arraycopy(buf, 4, address, 0, 4);
        InetSocketAddress IP = new InetSocketAddress(InetAddress.getByAddress(address), port);
        System.out.println("    Connect to " + IP.toString());
        if (!outChannel.connect(new InetSocketAddress(InetAddress.getByAddress(address), port))) {
            outKey.interestOps(SelectionKey.OP_CONNECT);
            System.out.println("Success");
        } else
            System.out.println("Failure");
        inAttachment.writeBuf.clear();//aka readBuf
        inAttachment.writeBuf.put(response);
        inKey.interestOps(SelectionKey.OP_WRITE);
        outAttachment.peer = inKey;
    }

    private Pair<InetAddress, String> parseDNSAnswer(ByteBuffer buf) throws IOException {
        Message message = new Message(buf.flip());
        Record[] answer = message.getSectionArray(Section.ANSWER);
        String name = null, CNAMEAlias = null, CNAMEName = null;
        InetAddress inetAddress = null;
        for (Record r : answer) {
            if (r.getType() == Type.CNAME) {
                CNAMEName = r.getName().toString();
                if (name == null)
                    name = CNAMEName.substring(0, CNAMEName.length() - 1);
                CNAMEAlias = ((CNAMERecord) r).getAlias().toString();
            }
        }
        for (Record r : answer) {
            if (r.getType() == Type.A) {
                String AAlias = r.getName().toString();
                if (CNAMEAlias != null && AAlias.equals(CNAMEAlias)) {
                    inetAddress = ((ARecord) r).getAddress();
                    break;
                } else if (CNAMEAlias == null) {
                    name = r.getName().toString();
                    name = name.substring(0, name.length() - 1);
                    inetAddress = ((ARecord) r).getAddress();
                    break;
                }
            }
        }
        return new Pair<>(inetAddress, name);
    }

    void readAndReplyDNS() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(Attachment.SIZE);
        int count = DNSChannel.read(buf);
        Pair<InetAddress, String> pair = parseDNSAnswer(buf);
        InetAddress inetAddress = pair.getKey();
        String name = pair.getValue();
        if (inetAddress == null) {
            namesToBeResolved.remove(name);
            return;
        }
        Iterator<Map.Entry<String, SelectionKey>> it = namesToBeResolved.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SelectionKey> e = it.next();
            if (e.getKey().equals(name)) {
                inKey = e.getValue();
                inAttachment = (Attachment) inKey.attachment();
                short port = inAttachment.port;
                outKey = inAttachment.peer;
                outChannel = (SocketChannel) outKey.channel();
                outAttachment = (Attachment) outKey.attachment();
                if (!outChannel.connect(new InetSocketAddress(inetAddress, port)))
                    outKey.interestOps(SelectionKey.OP_CONNECT);
                inAttachment.writeBuf.clear();//aka readBuf
                inAttachment.writeBuf.put(response);
                inKey.interestOps(SelectionKey.OP_WRITE);
                outAttachment.peer = inKey;
                it.remove();
            }
        }

    }

    private void writeDNS(byte[] buf) throws IOException {
        byte len = buf[4];
        byte[] domainBytes = new byte[len];
        System.arraycopy(buf, 5, domainBytes, 0, len);
        String domainName = new String(domainBytes, "UTF-8");
        short port = ByteBuffer.wrap(new byte[]{buf[5 + len], buf[5 + len + 1]}).getShort();
        System.out.println("    Trying to resolve " + domainName);
        Message message = new Message();
        Record record = Record.newRecord(new Name(domainName + "."), Type.A, DClass.IN);
        message.addRecord(record, Section.QUESTION);
        message.getHeader().setFlag(Flags.RD);
        byte[] DNSbuf = message.toWire();
        ByteBuffer bb = ByteBuffer.allocate(DNSbuf.length).put(DNSbuf).flip();
        System.out.println("    DNS Server " + DNSChannel.getRemoteAddress());
        DNSChannel.send(bb, DNSChannel.getRemoteAddress());
        inAttachment.port = port;
        namesToBeResolved.put(domainName, inKey);
    }

}
