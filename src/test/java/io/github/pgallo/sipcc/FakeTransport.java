package io.github.pgallo.sipcc;

import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.time.TimerService;
import io.github.pgallo.sipcc.transport.Transport;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Records every message sent, with the timer's time, instead of putting it on a socket. */
final class FakeTransport implements Transport {

    record Sent(long at, SipMessage message, InetSocketAddress to) {}

    final List<Sent> sent = new ArrayList<>();
    private final TimerService clock;

    FakeTransport(TimerService clock) {
        this.clock = clock;
    }

    @Override
    public void start(BiConsumer<SipMessage, InetSocketAddress> inbound) {
    }

    @Override
    public void send(SipMessage message, InetSocketAddress destination) {
        sent.add(new Sent(clock.now(), message, destination));
    }

    @Override
    public InetSocketAddress localAddress() {
        return new InetSocketAddress("127.0.0.1", 5070);
    }

    @Override
    public void close() {
    }

    List<Long> times(String what) {
        return sent.stream().filter(s -> what.equals(s.message().isRequest() ? s.message().method()
                : String.valueOf(s.message().status()))).map(Sent::at).toList();
    }
}
