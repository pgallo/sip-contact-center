package io.github.pgallo.sipcc.cc;

import java.util.List;

/** Call detail record. Times are milliseconds on the contact center's clock; -1 means "did not happen". */
public record Cdr(String callId, Direction direction, String caller, String dialed, String ivrPath, String queue,
                  long arrivedAt, long queuedAt, long answeredAt, long endedAt, List<String> agents, int transfers,
                  Disposition disposition) {

    public enum Direction { INBOUND, OUTBOUND }

    public enum Disposition { ANSWERED, ABANDONED, IVR_ONLY, FAILED }

    public long waitMs() {
        if (queuedAt < 0) {
            return 0;
        }
        return (answeredAt >= 0 ? answeredAt : endedAt) - queuedAt;
    }

    public long talkMs() {
        return answeredAt < 0 ? 0 : endedAt - answeredAt;
    }

    public static String header() {
        return "call_id,direction,caller,dialed,ivr_path,queue,arrived_s,wait_s,talk_s,agents,transfers,disposition";
    }

    public String csv() {
        return String.join(",", callId, direction.name(), caller, dialed, ivrPath, queue == null ? "" : queue,
                fmt(arrivedAt), fmt(waitMs()), fmt(talkMs()), String.join("|", agents), String.valueOf(transfers),
                disposition.name());
    }

    private static String fmt(long ms) {
        return String.format("%.1f", ms / 1000.0);
    }
}
