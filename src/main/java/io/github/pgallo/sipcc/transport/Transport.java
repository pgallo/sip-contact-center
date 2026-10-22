package io.github.pgallo.sipcc.transport;

import io.github.pgallo.sipcc.sip.SipMessage;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

/** Datagram-style SIP transport. Inbound messages are delivered on the owner's event loop. */
public interface Transport extends AutoCloseable {

    void start(BiConsumer<SipMessage, InetSocketAddress> inbound);

    void send(SipMessage message, InetSocketAddress destination);

    InetSocketAddress localAddress();

    @Override
    void close();
}
