package io.github.pgallo.sipcc.demo;

import io.github.pgallo.sipcc.cc.Agent;
import io.github.pgallo.sipcc.cc.QueueConfig;

import java.util.List;
import java.util.Map;

/** Synthetic contact-center configuration shared by the demo and the tests. */
public final class Scenario {

    public static final String DOMAIN = "cc.test";
    public static final String SERVICE = "support";

    private Scenario() {}

    public static List<Agent> agents() {
        return List.of(
                new Agent("2001", "Ana", Map.of("billing", 3, "general", 2)),
                new Agent("2002", "Ben", Map.of("technical", 3, "general", 1)),
                new Agent("2003", "Cleo", Map.of("technical", 2, "billing", 1)),
                new Agent("2004", "Dario", Map.of("sales", 3, "general", 2)),
                new Agent("2005", "Elsa", Map.of("general", 3, "sales", 1, "billing", 1)),
                new Agent("2006", "Femi", Map.of("technical", 1, "sales", 2, "general", 1)));
    }

    public static List<QueueConfig> queues() {
        return List.of(
                QueueConfig.of("billing", "billing", 2, 1).withOverflow(30_000, "general"),
                QueueConfig.of("technical", "technical", 2, 1).withOverflow(40_000, "general"),
                QueueConfig.of("outage", "technical", 1, 5),
                QueueConfig.of("sales", "sales", 2, 2).withOverflow(30_000, "general"),
                QueueConfig.of("general", "general", 1, 0));
    }

    public static String password(String user) {
        return "pw-" + user;
    }
}
