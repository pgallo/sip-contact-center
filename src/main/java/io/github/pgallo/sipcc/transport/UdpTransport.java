package io.github.pgallo.sipcc.transport;

import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.SipParseException;
import io.github.pgallo.sipcc.sip.SipParser;
import io.github.pgallo.sipcc.time.TimerService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * SIP over UDP on a {@link DatagramSocket}. A reader thread parses datagrams and hands them to the
 * event loop; malformed datagrams are counted and dropped, as RFC 3261 section 16.3 allows for
 * messages that cannot be parsed far enough to answer.
 */
public final class UdpTransport implements Transport {

    public static final AtomicLong SENT = new AtomicLong();
    public static final AtomicLong RECEIVED = new AtomicLong();
    public static final AtomicLong MALFORMED = new AtomicLong();

    private final DatagramSocket socket;
    private final TimerService loop;
    private Thread reader;

    public UdpTransport(TimerService loop, int port) {
        this.loop = loop;
        try {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            socket.setReceiveBufferSize(1 << 20);
        } catch (SocketException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void start(BiConsumer<SipMessage, InetSocketAddress> inbound) {
        reader = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (!socket.isClosed()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (IOException e) {
                    return;
                }
                RECEIVED.incrementAndGet();
                InetSocketAddress src = (InetSocketAddress) p.getSocketAddress();
                SipMessage msg;
                try {
                    msg = SipParser.parse(p.getData(), p.getLength());
                } catch (SipParseException e) {
                    MALFORMED.incrementAndGet();
                    continue;
                }
                loop.execute(() -> inbound.accept(msg, src));
            }
        }, "udp-" + socket.getLocalPort());
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public void send(SipMessage message, InetSocketAddress destination) {
        byte[] data = message.encode();
        try {
            socket.send(new DatagramPacket(data, data.length, destination));
            SENT.incrementAndGet();
        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.err.println("udp send to " + destination + " failed: " + e.getMessage());
            }
        }
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    @Override
    public void close() {
        socket.close();
    }
}
