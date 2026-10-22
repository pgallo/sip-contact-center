package io.github.pgallo.sipcc.platform.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client side of the manager protocol over TCP. Every action gets a unique ActionID and a
 * future; a reader thread completes the future when the matching response arrives (responses
 * may interleave with events and with responses to other actions) and passes events to a handler.
 */
public final class ManagerConnection implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;
    private final String banner;
    private final AtomicLong ids = new AtomicLong();
    private final Map<String, CompletableFuture<ManagerMessage>> pending = new ConcurrentHashMap<>();
    private final Consumer<ManagerMessage> events;
    private final Thread reader;

    private ManagerConnection(Socket socket, Consumer<ManagerMessage> events) throws IOException {
        this.socket = socket;
        this.events = events;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        this.banner = in.readLine();
        this.reader = new Thread(this::readLoop, "manager-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static ManagerConnection connect(InetSocketAddress address, Consumer<ManagerMessage> events,
                                            Duration timeout) throws IOException {
        Socket s = new Socket();
        s.connect(address, (int) timeout.toMillis());
        s.setTcpNoDelay(true);
        return new ManagerConnection(s, events);
    }

    public String banner() {
        return banner;
    }

    /** Sends an action; the returned future completes with the correlated response. */
    public CompletableFuture<ManagerMessage> send(ManagerMessage action) {
        String id = "A" + ids.incrementAndGet();
        action.put("ActionID", id);
        CompletableFuture<ManagerMessage> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            synchronized (out) {
                out.write(action.encode());
                out.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            f.completeExceptionally(e);
        }
        return f;
    }

    public void login(String user, String secret, Duration timeout) throws IOException {
        ManagerMessage resp;
        try {
            resp = send(ManagerMessage.action("Login").put("Username", user).put("Secret", secret))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("login failed: " + e, e);
        }
        if (!resp.isSuccess()) {
            throw new IOException("login rejected: " + resp.get("Message"));
        }
    }

    public int pendingActions() {
        return pending.size();
    }

    private void readLoop() {
        try {
            ManagerMessage m;
            while ((m = ManagerMessage.read(in)) != null) {
                if (m.isEvent()) {
                    events.accept(m);
                } else {
                    CompletableFuture<ManagerMessage> f = m.get("ActionID") == null ? null : pending.remove(m.get("ActionID"));
                    if (f != null) {
                        f.complete(m);
                    }
                }
            }
        } catch (IOException e) {
            // connection closed; fall through
        }
        IOException closed = new IOException("manager connection closed");
        pending.values().forEach(f -> f.completeExceptionally(closed));
        pending.clear();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
