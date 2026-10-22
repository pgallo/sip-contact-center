package io.github.pgallo.sipcc.demo;

import io.github.pgallo.sipcc.cc.Agent;
import io.github.pgallo.sipcc.cc.Cdr;
import io.github.pgallo.sipcc.cc.ContactCenter;
import io.github.pgallo.sipcc.cc.IvrMenu;
import io.github.pgallo.sipcc.cc.QueueConfig;
import io.github.pgallo.sipcc.cc.QueueStats;
import io.github.pgallo.sipcc.platform.manager.ManagerPlatform;
import io.github.pgallo.sipcc.platform.manager.SimulatedPbx;
import io.github.pgallo.sipcc.platform.sip.SipPlatform;
import io.github.pgallo.sipcc.time.EventLoop;
import io.github.pgallo.sipcc.time.TimerService;
import io.github.pgallo.sipcc.transport.UdpTransport;
import io.github.pgallo.sipcc.txn.SipTimers;
import io.github.pgallo.sipcc.txn.TransactionLayer;
import io.github.pgallo.sipcc.ua.UserAgent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Demo driver. Runs compressed simulated traffic against the contact-center core over either
 * platform adapter, then prints queue statistics, agent summary and a CDR summary.
 *
 * <pre>
 *   --platform sip|manager   which telephony adapter to use (default sip)
 *   --seconds N              simulated seconds of arrivals (default 60)
 *   --speed X                simulated seconds per real second (default 20)
 *   --seed S                 random seed (default 7)
 *   --load [--calls N]       SIP throughput run with short calls (default 2000 calls)
 *   --verbose                print the full event timeline
 * </pre>
 */
public final class Main {

    private static final String[][] PATHS = { // IVR digits and their weight
            {"1", "30"}, {"21", "25"}, {"22", "8"}, {"3", "20"}, {"0", "12"}, {"", "5"}};

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        if (opt.containsKey("load")) {
            runLoad(Integer.parseInt(opt.getOrDefault("calls", "2000")));
            return;
        }
        String platform = opt.getOrDefault("platform", "sip");
        int seconds = Integer.parseInt(opt.getOrDefault("seconds", "60"));
        double speed = Double.parseDouble(opt.getOrDefault("speed", "20"));
        long seed = Long.parseLong(opt.getOrDefault("seed", "7"));
        boolean verbose = opt.containsKey("verbose");
        if (platform.equals("manager")) {
            runManager(seconds, speed, seed, verbose);
        } else {
            runSip(seconds, speed, seed, verbose);
        }
    }

    // ---------------------------------------------------------------- SIP platform

    private static void runSip(int seconds, double speed, long seed, boolean verbose) throws Exception {
        Random rnd = new Random(seed);
        try (EventLoop loop = new EventLoop("cc-loop")) {
            TimerService sim = loop.scaled(speed);
            SipTimers timers = SipTimers.RFC3261;
            List<Agent> agents = Scenario.agents();
            Map<String, String> accounts = new HashMap<>();
            agents.forEach(a -> accounts.put(a.extension(), Scenario.password(a.extension())));
            SipPlatform platform = new SipPlatform(loop, 0, Scenario.DOMAIN, Scenario.SERVICE, accounts,
                    agents.stream().map(Agent::extension).collect(Collectors.toSet()), timers);
            ContactCenter cc = new ContactCenter(sim, platform, IvrMenu.standard(), agents, Scenario.queues(), 5_000, 8_000);
            Timeline timeline = new Timeline(verbose);
            cc.setLog(timeline);
            platform.start(cc);

            System.out.printf("SIP contact center on udp %s, domain %s, %d agents, %d s simulated at %.0fx%n",
                    hostPort(platform.address()), Scenario.DOMAIN, agents.size(), seconds, speed);
            List<SimAgent> simAgents = new ArrayList<>();
            for (Agent a : agents) {
                simAgents.add(loop.call(() -> {
                    UdpTransport t = new UdpTransport(loop, 0);
                    UserAgent ua = new UserAgent(a.extension(), Scenario.DOMAIN, Scenario.password(a.extension()),
                            new TransactionLayer(t, loop, timers), loop, timers, platform.address()).start();
                    SimAgent s = new SimAgent(ua, cc, sim, new Random(rnd.nextLong()), SimAgent.Profile.DEMO);
                    s.start();
                    return s;
                }));
            }
            await(() -> cc.agents().stream().allMatch(a -> a.state() == Agent.State.READY), 5_000);
            System.out.println("agents registered (digest) and READY\n");

            List<SimCaller> callers = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                String user = String.format("caller%02d", i);
                callers.add(loop.call(() -> {
                    UdpTransport t = new UdpTransport(loop, 0);
                    UserAgent ua = new UserAgent(user, Scenario.DOMAIN, null, new TransactionLayer(t, loop, timers),
                            loop, timers, platform.address()).start();
                    return new SimCaller(ua, sim, 1_200);
                }));
            }
            AtomicInteger placed = new AtomicInteger();
            AtomicInteger busyRejected = new AtomicInteger();
            long t = 0;
            List<Long> arrivals = new ArrayList<>();
            while (true) {
                t += (long) (-Math.log(1 - rnd.nextDouble()) * 3_500);
                if (t > seconds * 1000L) {
                    break;
                }
                arrivals.add(t);
            }
            CountDownLatch arrivalsDone = new CountDownLatch(1);
            loop.execute(() -> {
                for (int i = 0; i < arrivals.size(); i++) {
                    boolean last = i == arrivals.size() - 1;
                    String digits = pickPath(rnd);
                    long patience = 25_000 + rnd.nextInt(50_000);
                    sim.schedule(arrivals.get(i), () -> {
                        SimCaller free = callers.stream().filter(c -> !c.busy()).findFirst().orElse(null);
                        if (free == null) {
                            busyRejected.incrementAndGet();
                        } else {
                            placed.incrementAndGet();
                            free.call(digits, patience, () -> { });
                        }
                        if (last) {
                            arrivalsDone.countDown();
                        }
                    });
                }
            });
            arrivalsDone.await((long) (seconds * 1000 / speed) + 10_000, TimeUnit.MILLISECONDS);
            await(() -> cc.activeCalls() == 0 && loop.call(() -> callers.stream().noneMatch(SimCaller::busy)),
                    (long) (300_000 / speed) + 10_000);
            Thread.sleep((long) (6_000 / speed) + 50); // let the last wrap-up timers fire

            timeline.flush();
            System.out.printf("%ncalls placed: %d (no free caller line: %d), blind transfers: %d, attended transfers: %d%n",
                    placed.get(), busyRejected.get(), SimAgent.BLIND.get(), SimAgent.ATTENDED.get());
            report(cc);
            System.out.printf("%nSIP: %d datagrams sent, %d received, %d malformed, %d requests proxied%n",
                    UdpTransport.SENT.get(), UdpTransport.RECEIVED.get(), UdpTransport.MALFORMED.get(),
                    loop.call(platform::proxiedRequests));
            platform.close();
        }
    }

    // ---------------------------------------------------------------- manager-interface platform

    private static void runManager(int seconds, double speed, long seed, boolean verbose) throws Exception {
        Random rnd = new Random(seed);
        try (EventLoop loop = new EventLoop("cc-loop");
             SimulatedPbx pbx = new SimulatedPbx("ccmanager", "synthetic-secret", (long) (1_500 / speed))) {
            TimerService sim = loop.scaled(speed);
            ManagerPlatform platform = new ManagerPlatform(pbx.address(), "ccmanager", "synthetic-secret", loop);
            List<Agent> agents = Scenario.agents();
            ContactCenter cc = new ContactCenter(sim, platform, IvrMenu.standard(), agents, Scenario.queues(), 5_000, 8_000);
            Timeline timeline = new Timeline(verbose);
            cc.setLog(timeline);
            platform.start(cc);
            System.out.printf("manager-interface PBX at tcp %s, banner \"%s\", %d agents, %d s simulated at %.0fx%n",
                    hostPort(pbx.address()), platform.banner(), agents.size(), seconds, speed);
            for (Agent a : agents) {
                pbx.agentLogin(a.extension());
                pbx.agentPaused(a.extension(), false);
            }
            await(() -> cc.agents().stream().allMatch(a -> a.state() == Agent.State.READY), 5_000);
            System.out.println("agents logged in and unpaused via manager events\n");

            Set<String> bridged = ConcurrentHashMap.newKeySet();
            pbx.onBridge((ref, agent) -> {
                bridged.add(ref);
                long talk = Math.max(3_000, (long) (-Math.log(1 - rnd.nextDouble()) * 20_000));
                sim.schedule(talk, () -> pbx.agentHangup(agent));
            });
            CountDownLatch arrivalsDone = new CountDownLatch(1);
            AtomicInteger placed = new AtomicInteger();
            loop.execute(() -> {
                long t = 0;
                List<Long> arrivals = new ArrayList<>();
                while ((t += (long) (-Math.log(1 - rnd.nextDouble()) * 3_500)) <= seconds * 1000L) {
                    arrivals.add(t);
                }
                for (int i = 0; i < arrivals.size(); i++) {
                    boolean last = i == arrivals.size() - 1;
                    String digits = pickPath(rnd);
                    long patience = 25_000 + rnd.nextInt(50_000);
                    String number = String.format("+99900%06d", 100 + i);
                    sim.schedule(arrivals.get(i), () -> {
                        placed.incrementAndGet();
                        String ref = pbx.incomingCall(number, "800");
                        for (int d = 0; d < digits.length(); d++) {
                            char digit = digits.charAt(d);
                            sim.schedule(1_200L * (d + 1), () -> pbx.dtmf(ref, digit));
                        }
                        sim.schedule(patience, () -> {
                            if (!bridged.contains(ref)) {
                                pbx.callerHangup(ref);
                            }
                        });
                        if (last) {
                            arrivalsDone.countDown();
                        }
                    });
                }
            });
            arrivalsDone.await((long) (seconds * 1000 / speed) + 10_000, TimeUnit.MILLISECONDS);
            await(() -> cc.activeCalls() == 0, (long) (300_000 / speed) + 10_000);
            Thread.sleep((long) (6_000 / speed) + 50);
            timeline.flush();
            System.out.printf("%ncalls placed: %d%n", placed.get());
            report(cc);
            List<String> actions = pbx.actionLog();
            Map<String, Long> byAction = actions.stream()
                    .collect(Collectors.groupingBy(s -> s.split(" ")[0], java.util.TreeMap::new, Collectors.counting()));
            System.out.printf("%nmanager actions sent by the adapter: %s%n", byAction);
            platform.close();
        }
    }

    // ---------------------------------------------------------------- load mode

    private static void runLoad(int totalCalls) throws Exception {
        try (EventLoop loop = new EventLoop("cc-loop")) {
            SipTimers timers = SipTimers.RFC3261;
            int nAgents = 24;
            List<Agent> agents = new ArrayList<>();
            Map<String, String> accounts = new HashMap<>();
            for (int i = 0; i < nAgents; i++) {
                String ext = String.valueOf(3000 + i);
                agents.add(new Agent(ext, "load-" + i, Map.of("general", 1)));
                accounts.put(ext, Scenario.password(ext));
            }
            SipPlatform platform = new SipPlatform(loop, 0, Scenario.DOMAIN, Scenario.SERVICE, accounts,
                    accounts.keySet(), timers);
            ContactCenter cc = new ContactCenter(loop, platform, IvrMenu.standard(), agents,
                    List.of(QueueConfig.of("general", "general", 1, 0)), 0, 60_000);
            platform.start(cc);
            for (Agent a : agents) {
                loop.call(() -> {
                    UserAgent ua = new UserAgent(a.extension(), Scenario.DOMAIN, Scenario.password(a.extension()),
                            new TransactionLayer(new UdpTransport(loop, 0), loop, timers), loop, timers,
                            platform.address()).start();
                    new SimAgent(ua, cc, loop, new Random(1), SimAgent.Profile.LOAD).start();
                    return null;
                });
            }
            await(() -> cc.agents().stream().allMatch(a -> a.state() == Agent.State.READY), 5_000);
            AtomicInteger started = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(totalCalls);
            List<SimCaller> callers = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                String user = "load-caller" + i;
                callers.add(loop.call(() -> new SimCaller(new UserAgent(user, Scenario.DOMAIN, null,
                        new TransactionLayer(new UdpTransport(loop, 0), loop, timers), loop, timers,
                        platform.address()).start(), loop, 1)));
            }
            long sent0 = UdpTransport.SENT.get();
            long t0 = System.nanoTime();
            for (SimCaller c : callers) {
                loop.execute(() -> next(c, started, totalCalls, done));
            }
            boolean finished = done.await(120, TimeUnit.SECONDS);
            double secs = (System.nanoTime() - t0) / 1e9;
            long datagrams = UdpTransport.SENT.get() - sent0;
            List<Cdr> cdrs = cc.cdrs();
            long answered = cdrs.stream().filter(c -> c.disposition() == Cdr.Disposition.ANSWERED).count();
            System.out.printf("load: %d calls (%d answered by agents) in %.2f s%s%n", cdrs.size(), answered, secs,
                    finished ? "" : " (timed out)");
            System.out.printf("      %.0f calls/s, %.0f SIP datagrams/s, %.1f datagrams per call, 1 event-loop thread%n",
                    cdrs.size() / secs, datagrams / secs, (double) datagrams / Math.max(1, cdrs.size()));
            platform.close();
        }
    }

    private static void next(SimCaller c, AtomicInteger started, int total, CountDownLatch done) {
        if (started.incrementAndGet() > total) {
            return;
        }
        c.call("0", 60_000, () -> {
            done.countDown();
            next(c, started, total, done);
        });
    }

    // ---------------------------------------------------------------- reporting

    static void report(ContactCenter cc) {
        System.out.println("\nqueue        offered answered abandoned overflow   SL(20s)   ASA(s)  abandon%");
        for (String q : cc.queueNames()) {
            QueueStats s = cc.stats().get(q);
            System.out.printf("%-12s %7d %8d %9d %8d %8.0f%% %8.1f %8.0f%%%n", q, s.offeredCount(), s.answeredCount(),
                    s.abandonedCount(), s.overflowCount(), s.serviceLevel() * 100, s.asaMs() / 1000, s.abandonRate() * 100);
        }
        System.out.println("\nagent  name    handled  talk(s)  state");
        for (Agent a : cc.agents()) {
            System.out.printf("%-6s %-7s %7d %8.0f  %s%n", a.extension(), a.name(), a.handled(), a.talkMs() / 1000.0, a.state());
        }
        List<Cdr> cdrs = cc.cdrs();
        Map<Cdr.Disposition, Long> byDisp = cdrs.stream()
                .collect(Collectors.groupingBy(Cdr::disposition, () -> new java.util.EnumMap<>(Cdr.Disposition.class),
                        Collectors.counting()));
        int transfers = cdrs.stream().mapToInt(Cdr::transfers).sum();
        double avgWait = cdrs.stream().filter(c -> c.queuedAt() >= 0).mapToLong(Cdr::waitMs).average().orElse(0) / 1000;
        double avgTalk = cdrs.stream().filter(c -> c.answeredAt() >= 0).mapToLong(Cdr::talkMs).average().orElse(0) / 1000;
        System.out.printf("%nCDRs: %d %s, %d transfers, avg wait %.1f s, avg talk %.1f s%n", cdrs.size(), byDisp,
                transfers, avgWait, avgTalk);
        System.out.println(Cdr.header());
        cdrs.stream().limit(8).forEach(c -> System.out.println(c.csv()));
        if (cdrs.size() > 8) {
            System.out.println("... " + (cdrs.size() - 8) + " more");
        }
    }

    private static String hostPort(java.net.InetSocketAddress a) {
        return a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    private static String pickPath(Random rnd) {
        int total = 0;
        for (String[] p : PATHS) {
            total += Integer.parseInt(p[1]);
        }
        int r = rnd.nextInt(total);
        for (String[] p : PATHS) {
            r -= Integer.parseInt(p[1]);
            if (r < 0) {
                return p[0];
            }
        }
        return "0";
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                System.out.println("(timed out waiting for the simulation to settle)");
                return;
            }
            Thread.sleep(10);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + a);
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(key, args[++i]);
            } else {
                m.put(key, "true");
            }
        }
        return m;
    }

    /** Prints the first part of the event timeline (all of it with --verbose). */
    private static final class Timeline implements java.util.function.Consumer<String> {
        private final boolean verbose;
        private int lines;
        private int suppressed;

        Timeline(boolean verbose) {
            this.verbose = verbose;
        }

        @Override
        public synchronized void accept(String line) {
            if (verbose || lines < 40) {
                System.out.println(line);
                lines++;
            } else {
                suppressed++;
            }
        }

        synchronized void flush() {
            if (suppressed > 0) {
                System.out.println("  ... " + suppressed + " more events (use --verbose)");
            }
        }
    }
}
