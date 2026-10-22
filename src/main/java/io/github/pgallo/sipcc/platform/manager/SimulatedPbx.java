package io.github.pgallo.sipcc.platform.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * An in-process PBX that speaks the manager protocol over TCP, for tests and the demo. It models
 * channels (trunk and agent), bridging and hangup, and exposes a simulation API to inject caller
 * activity. All state is confined to one executor thread.
 */
public final class SimulatedPbx implements AutoCloseable {

    public static final String BANNER = "Simulated PBX Manager Interface/1.0";

    private static final class Channel {
        final String name;
        final String callRef;
        final String agent;
        Channel peer;

        Channel(String name, String callRef, String agent) {
            this.name = name;
            this.callRef = callRef;
            this.agent = agent;
        }
    }

    private final class Client {
        final Socket socket;
        final Writer out;
        boolean authenticated;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        }

        void send(String text) {
            try {
                synchronized (out) {
                    out.write(text);
                    out.flush();
                }
            } catch (IOException e) {
                clients.remove(this);
            }
        }
    }

    private final ServerSocket server;
    private final String user;
    private final String secret;
    private final long ringDelayMs;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "simulated-pbx");
        t.setDaemon(true);
        return t;
    });
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Set<String> loggedIn = new HashSet<>();
    private final List<String> actionLog = Collections.synchronizedList(new ArrayList<>());
    private volatile BiConsumer<String, String> bridgeObserver = (ref, agent) -> { };
    private int uid = 1000;

    public SimulatedPbx(String user, String secret, long ringDelayMs) throws IOException {
        this.user = user;
        this.secret = secret;
        this.ringDelayMs = ringDelayMs;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "simulated-pbx-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) server.getLocalSocketAddress();
    }

    public void onBridge(BiConsumer<String, String> observer) {
        this.bridgeObserver = observer;
    }

    public List<String> actionLog() {
        synchronized (actionLog) {
            return List.copyOf(actionLog);
        }
    }

    // ---------------------------------------------------------------- protocol side

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Client c = new Client(s);
                clients.add(c);
                c.send(BANNER + "\r\n");
                Thread t = new Thread(() -> readLoop(c), "simulated-pbx-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void readLoop(Client c) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.socket.getInputStream(), StandardCharsets.UTF_8))) {
            ManagerMessage m;
            while ((m = ManagerMessage.read(in)) != null) {
                ManagerMessage action = m;
                exec.execute(() -> handle(c, action));
            }
        } catch (IOException e) {
            // client went away
        }
        clients.remove(c);
    }

    private void handle(Client c, ManagerMessage a) {
        String action = a.get("Action");
        String id = a.get("ActionID");
        if (action == null) {
            reply(c, false, id, "Missing action in request");
            return;
        }
        if (!c.authenticated && !action.equalsIgnoreCase("Login")) {
            reply(c, false, id, "Permission denied");
            return;
        }
        actionLog.add(action + (a.get("Channel") == null ? "" : " " + a.get("Channel"))
                + (a.get("Exten") == null ? "" : " -> " + a.get("Exten")) + (a.get("File") == null ? "" : " " + a.get("File")));
        Channel ch = a.get("Channel") == null ? null : channels.get(a.get("Channel"));
        switch (action) {
            case "Login" -> {
                c.authenticated = user.equals(a.get("Username")) && secret.equals(a.get("Secret"));
                reply(c, c.authenticated, id, c.authenticated ? "Authentication accepted" : "Authentication failed");
            }
            case "Ping" -> reply(c, true, id, "Pong");
            case "Playback", "MusicOnHold" -> reply(c, ch != null, id, ch != null ? "Queued" : "No such channel");
            case "Hangup" -> {
                reply(c, ch != null, id, ch != null ? "Channel hungup" : "No such channel");
                if (ch != null) {
                    hangup(ch);
                }
            }
            case "Redirect" -> {
                String ext = a.get("Exten");
                if (ch == null || ch.peer != null) {
                    reply(c, false, id, "No such channel");
                } else if (!loggedIn.contains(ext)) {
                    reply(c, false, id, "Extension not available");
                } else {
                    reply(c, true, id, "Redirect successful");
                    exec.schedule(() -> {
                        if (channels.get(ch.name) == ch && ch.peer == null) {
                            bridge(ch, ext);
                        }
                    }, ringDelayMs, TimeUnit.MILLISECONDS);
                }
            }
            case "Originate" -> {
                String chan = String.valueOf(a.get("Channel"));
                String ext = chan.startsWith("Agent/") ? chan.substring(6) : "";
                String ref = null;
                for (String v : a.getAll("Variable")) {
                    if (v.startsWith("CALLREF=")) {
                        ref = v.substring(8);
                    }
                }
                if (!loggedIn.contains(ext) || ref == null) {
                    reply(c, false, id, "Originate failed");
                } else {
                    reply(c, true, id, "Originate successfully queued");
                    String callRef = ref;
                    String dest = a.get("Exten");
                    exec.schedule(() -> {
                        Channel far = newChannel("Trunk/" + dest + "-" + (++uid), callRef, null);
                        broadcast(ManagerMessage.event("Newchannel").put("Channel", far.name).put("CallRef", callRef)
                                .put("Direction", "outbound").put("Exten", dest));
                        bridge(far, ext);
                    }, ringDelayMs, TimeUnit.MILLISECONDS);
                }
            }
            case "Logoff" -> {
                reply(c, true, id, "Goodbye");
                try {
                    c.socket.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
            default -> reply(c, false, id, "Invalid/unknown command");
        }
    }

    private void reply(Client c, boolean ok, String id, String message) {
        c.send(ManagerMessage.response(ok, id, message).encode());
    }

    private void broadcast(ManagerMessage event) {
        String text = event.encode();
        for (Client c : clients) {
            if (c.authenticated) {
                c.send(text);
            }
        }
    }

    private Channel newChannel(String name, String callRef, String agent) {
        Channel ch = new Channel(name, callRef, agent);
        channels.put(name, ch);
        return ch;
    }

    private void bridge(Channel caller, String agent) {
        Channel a = newChannel("Agent/" + agent + "-" + (++uid), caller.callRef, agent);
        a.peer = caller;
        caller.peer = a;
        broadcast(ManagerMessage.event("Bridge").put("Bridgestate", "Link").put("Channel1", caller.name)
                .put("Channel2", a.name).put("CallRef", caller.callRef).put("Agent", agent));
        bridgeObserver.accept(caller.callRef, agent);
    }

    private void hangup(Channel ch) {
        Channel peer = ch.peer;
        if (peer == null) {
            destroy(ch);
            return;
        }
        Channel agentSide = ch.agent != null ? ch : peer;
        Channel other = agentSide == ch ? peer : ch;
        destroy(agentSide); // a two-party bridge ends for both sides; the agent leg is reported first
        destroy(other);
    }

    private void destroy(Channel ch) {
        if (channels.remove(ch.name) != null) {
            broadcast(ManagerMessage.event("Hangup").put("Channel", ch.name).put("CallRef", ch.callRef)
                    .put("Cause", "16"));
        }
    }

    // ---------------------------------------------------------------- simulation API

    /** A caller dials {@code exten}; returns the call reference used in all later events. */
    public String incomingCall(String callerNumber, String exten) {
        return onExec(() -> {
            String ref = String.valueOf(++uid);
            Channel ch = newChannel("Trunk/" + callerNumber + "-" + ref, ref, null);
            broadcast(ManagerMessage.event("Newchannel").put("Channel", ch.name).put("CallRef", ref)
                    .put("Direction", "inbound").put("CallerIDNum", callerNumber).put("Exten", exten));
            return ref;
        });
    }

    public void dtmf(String callRef, char digit) {
        onExec(() -> {
            callerChannel(callRef).ifPresent(ch -> broadcast(ManagerMessage.event("DTMF").put("Channel", ch.name)
                    .put("CallRef", callRef).put("Digit", String.valueOf(digit)).put("Direction", "Received")));
            return null;
        });
    }

    public void callerHangup(String callRef) {
        onExec(() -> {
            callerChannel(callRef).ifPresent(this::hangup);
            return null;
        });
    }

    public boolean agentHangup(String agent) {
        return onExec(() -> {
            for (Channel ch : List.copyOf(channels.values())) {
                if (agent.equals(ch.agent)) {
                    hangup(ch);
                    return true;
                }
            }
            return false;
        });
    }

    public void agentLogin(String agent) {
        onExec(() -> {
            loggedIn.add(agent);
            broadcast(ManagerMessage.event("AgentLogin").put("Agent", agent));
            return null;
        });
    }

    public void agentPaused(String agent, boolean paused) {
        onExec(() -> {
            broadcast(ManagerMessage.event("AgentPaused").put("Agent", agent).put("Paused", paused ? "1" : "0"));
            return null;
        });
    }

    public int activeChannels() {
        return onExec(channels::size);
    }

    private java.util.Optional<Channel> callerChannel(String callRef) {
        return channels.values().stream().filter(c -> c.agent == null && callRef.equals(c.callRef)).findFirst();
    }

    private <T> T onExec(Callable<T> task) {
        try {
            return exec.submit(task).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        for (Client c : clients) {
            try {
                c.socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        exec.shutdownNow();
    }
}
