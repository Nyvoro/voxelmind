package com.voxelmind.local.mind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What happened to and around one companion, in order. This is the companion's
 * short-term memory: every decision round renders the latest rows, and the
 * memory writer folds older rows into long-term text.
 */
public final class Timeline {
    public static final int KEEP = 300;

    /** {@code kind}: chat_in, whisper_in, chat_out, action, result, damage, death, respawn, presence, note. */
    public static final class Event {
        public long seq;
        public long epochMs;
        public String kind;
        public String text;
        public boolean fromOwner;
        /** For results: done, failed or preempted. */
        public String outcome;

        public Event() {}

        Event(long seq, long epochMs, String kind, String text, boolean fromOwner, String outcome) {
            this.seq = seq;
            this.epochMs = epochMs;
            this.kind = kind;
            this.text = text;
            this.fromOwner = fromOwner;
            this.outcome = outcome;
        }
    }

    public long nextSeq = 1;
    public List<Event> events = new ArrayList<>();

    public synchronized Event add(String kind, String text, boolean fromOwner) {
        return add(kind, text, fromOwner, null);
    }

    public synchronized Event add(String kind, String text, boolean fromOwner, String outcome) {
        Event event = new Event(nextSeq++, System.currentTimeMillis(), kind, text, fromOwner, outcome);
        events.add(event);
        if (events.size() > KEEP) events.subList(0, events.size() - KEEP).clear();
        return event;
    }

    public synchronized List<Event> last(int count) {
        int from = Math.max(0, events.size() - count);
        return new ArrayList<>(events.subList(from, events.size()));
    }

    public synchronized List<Event> since(long seqExclusive) {
        List<Event> out = new ArrayList<>();
        for (Event event : events) if (event.seq > seqExclusive) out.add(event);
        return out;
    }

    public synchronized long lastSeq() {
        return nextSeq - 1;
    }

    /** Numbered rows, oldest first, with relative age. */
    public static String render(List<Event> rows, long now) {
        StringBuilder out = new StringBuilder();
        int n = 1;
        for (Event event : rows) {
            out.append(n++).append(". (").append(age(now - event.epochMs)).append(") ");
            out.append(line(event)).append('\n');
        }
        return out.toString();
    }

    public static String line(Event event) {
        return switch (event.kind) {
            case "result" -> mark(event.outcome) + " " + event.text;
            // Stored as: text" (mode, audience)
            case "chat_out" -> "me: \"" + event.text;
            case "said" -> "me: \"" + event.text + "\"";
            default -> event.text;
        };
    }

    private static String mark(String outcome) {
        if (outcome == null) return "•";
        return switch (outcome) {
            case "done" -> "✓";
            case "preempted" -> "⊘";
            default -> "✗";
        };
    }

    public static String age(long millis) {
        Duration d = Duration.ofMillis(Math.max(0, millis));
        if (d.toSeconds() < 60) return d.toSeconds() + "s ago";
        if (d.toMinutes() < 60) return d.toMinutes() + "m ago";
        if (d.toHours() < 48) return d.toHours() + "h ago";
        return d.toDays() + "d ago";
    }
}
