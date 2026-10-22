package io.github.pgallo.sipcc.cc;

import java.util.Map;

/** A DTMF-driven IVR menu tree. Each node has a prompt and maps digits to actions. */
public final class IvrMenu {

    public sealed interface Action permits Goto, Enqueue, Repeat {}

    public record Goto(String node) implements Action {}

    public record Enqueue(String queue) implements Action {}

    public record Repeat() implements Action {}

    public record Node(String name, String prompt, Map<Character, Action> options) {}

    private final Map<String, Node> nodes;
    private final String root;
    private final String defaultQueue;

    public IvrMenu(Map<String, Node> nodes, String root, String defaultQueue) {
        this.nodes = Map.copyOf(nodes);
        this.root = root;
        this.defaultQueue = defaultQueue;
    }

    /**
     * main:  1 billing, 2 technical support menu, 3 sales, 0 general, * repeat
     * tech:  1 technical, 2 service outage (high priority), * back to main
     */
    public static IvrMenu standard() {
        return new IvrMenu(Map.of(
                "main", new Node("main", "main-menu", Map.of(
                        '1', new Enqueue("billing"), '2', new Goto("tech"), '3', new Enqueue("sales"),
                        '0', new Enqueue("general"), '*', new Repeat())),
                "tech", new Node("tech", "tech-menu", Map.of(
                        '1', new Enqueue("technical"), '2', new Enqueue("outage"), '*', new Goto("main")))),
                "main", "general");
    }

    public String root() {
        return root;
    }

    public String defaultQueue() {
        return defaultQueue;
    }

    public Node node(String name) {
        return nodes.get(name);
    }

    /** Returns the action for a digit at a node, or null for an invalid choice. */
    public Action select(String node, char digit) {
        Node n = nodes.get(node);
        return n == null ? null : n.options().get(digit);
    }
}
