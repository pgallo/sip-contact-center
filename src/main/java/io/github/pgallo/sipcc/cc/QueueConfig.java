package io.github.pgallo.sipcc.cc;

/**
 * A queue: the skill (and minimum level) an agent needs, a priority used when agents choose
 * between queues, an optional overflow skill that widens the agent pool after a wait threshold,
 * and the service-level threshold used for reporting.
 */
public record QueueConfig(String name, String skill, int minLevel, int priority,
                          long overflowAfterMs, String overflowSkill, long serviceLevelMs) {

    public static QueueConfig of(String name, String skill, int minLevel, int priority) {
        return new QueueConfig(name, skill, minLevel, priority, 0, null, 20_000);
    }

    public QueueConfig withOverflow(long afterMs, String skill) {
        return new QueueConfig(name, this.skill, minLevel, priority, afterMs, skill, serviceLevelMs);
    }
}
