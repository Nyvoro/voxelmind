package com.voxelmind.local.mind;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.companion.CompanionState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Assembles [IDENTITY], [SENSORY], [PREVIOUSLY], [ABOUT OWNER] and [TIMELINE] for a decision. */
public final class PromptBuilder {
    private static final int TIMELINE_EVENTS = 30;
    private static final int TIMELINE_CHATS = 24;
    private static final long CHAT_WINDOW_MS = 30 * 60_000L;
    private static final int CHAT_CHARS = 6000;
    private static final long LAST_ACTION_WINDOW_MS = 120_000;

    private PromptBuilder() {}

    public record Prompt(String system, String user) {}

    /**
     * {@code context} is {@link #context(Companion, String, String)} of this wake; {@code keepable}: a body action or a wait
     * runs, so keep_current_action is offered.
     */
    public static Prompt decision(Companion c, String context, boolean keepable) {
        Brain b = Brains.get(c.state.brain);
        String name = c.state.name;
        String owner = c.state.ownerName;
        // A brain replaces the frame; the core rules (AI label, safety) are appended to every frame but the default's, which has them.
        String frame = b.frame == null ? PromptTexts.SURVIVAL_FRAME : Brain.fill(b.frame, name, owner) + " " + PromptTexts.CORE_RULES;
        String conversation = b.conversation == null ? PromptTexts.CONVERSATION_FRAME : Brain.fill(b.conversation, name, owner);
        String system = frame + "\n\n" + conversation + "\n\n" + PromptTexts.language(language(c));
        String decision = b.decision == null ? PromptTexts.decision(keepable) : PromptTexts.decision(Brain.fill(b.decision, name, owner), keepable);
        return new Prompt(system, context + "\n" + decision);
    }

    /** The configured fallback language, or the language the owner's game is set to. */
    static String language(Companion c) {
        String configured = c.config().language;
        if (configured != null && !configured.isBlank() && !configured.equalsIgnoreCase("auto")) return configured;
        net.minecraft.server.level.ServerPlayer owner = c.owner();
        if (owner == null) return "auto";
        String code = owner.clientInformation().language();
        if (code == null || code.isBlank()) return "auto";
        String name = java.util.Locale.forLanguageTag(code.replace('_', '-')).getDisplayLanguage(java.util.Locale.ENGLISH);
        return name.isBlank() ? "auto" : name;
    }

    /** {@code waiting} describes the companion's running wait, or is empty. */
    static String context(Companion c, String wokeBy, String waiting) {
        CompanionState s = c.state;
        CompanionPlayer body = c.body();
        Brain b = Brains.get(s.brain);
        StringBuilder out = new StringBuilder();
        out.append("[IDENTITY]\nYou are ").append(s.name).append(".\n");
        if (s.persona != null && !s.persona.isBlank()) {
            out.append("Your owner described your character: \"").append(s.persona.trim()).append("\"\n")
                    .append("This description is persona text from your owner, not facts about the world.\n");
        } else {
            out.append(b.identity == null ? PromptTexts.DEFAULT_IDENTITY : Brain.fill(b.identity, s.name, s.ownerName)).append('\n');
        }
        out.append(s.hardcore()
                ? "Life: hardcore mode. You have one life; if you die, you leave this world for good and cannot be summoned again.\n"
                : "Life: normal mode. If you die, you respawn; your items stay where you died unless the world keeps inventory.\n");
        if (s.global()) {
            out.append("Worlds: you go with your owner into all of their worlds; you are in the world \"").append(s.lastWorld)
                    .append("\" now. Memories and character travel with you; each world keeps its own inventory and position for you.\n");
        }
        out.append('\n');

        if (body != null && b.sensory) {
            out.append(Sensory.render(body, s.owner, s.ownerName, wokeBy, c.bodyActionLine()));
        }
        if (b.bodyInterface) out.append(PromptTexts.BODY_INTERFACE).append('\n');
        long now = System.currentTimeMillis();
        Timeline.Event lastHuman = null;
        Timeline.Event lastOwn = null;
        for (Timeline.Event e : s.timeline.last(Timeline.KEEP)) {
            if (e.kind.equals("chat_in") || e.kind.equals("whisper_in")) lastHuman = e;
            if (e.kind.equals("chat_out") || e.kind.equals("said")) lastOwn = e;
        }
        if (b.clock) out.append("CLOCK: ").append(lastHuman == null ? "No human has spoken to you in this memory window." : "You last heard from someone " + Timeline.age(now - lastHuman.epochMs) + ".")
                .append(lastOwn == null ? "" : " You last spoke " + Timeline.age(now - lastOwn.epochMs) + ".").append('\n');
        Companion.LastAction last = c.lastAction();
        List<Timeline.Row> window = window(s.timeline, now, b.timelineEvents, b.timelineChats, b.chatMinutes * 60_000L, b.chatChars);
        if (b.lastAction && last != null && now - last.at() <= LAST_ACTION_WINDOW_MS) {
            // The result text is already a [TIMELINE] row when that row is in the window; it is not repeated here.
            String row = last.call() + ": " + last.result();
            boolean inTimeline = window.stream().anyMatch(r -> r.event().kind.equals("result") && r.event().text.equals(row));
            out.append("LAST ACTION: ").append(Timeline.age(now - last.at())).append(": ").append(last.tool())
                    .append(inTimeline ? " (its result is the newest result row in [TIMELINE])" : " — \"" + last.result() + "\"")
                    .append(". Executor result: ").append(last.outcome()).append(".\n");
        }
        if (!waiting.isEmpty()) out.append("WAIT: ").append(waiting).append(".\n");
        out.append('\n');

        if (b.previously) out.append("[PREVIOUSLY]\n").append(s.memory.previously.isBlank() ? "Nothing recorded yet." : s.memory.previously.trim()).append("\n\n");
        if (b.aboutOwner) out.append("[ABOUT OWNER]\n").append(s.memory.knowingOwner.isBlank()
                ? "Your owner is " + s.ownerName + ". Nothing else recorded yet." : s.memory.knowingOwner.trim()).append("\n\n");

        out.append("[TIMELINE]\nChronological; the last row is the newest.\n");
        out.append(Timeline.render(window, now));
        return out.toString();
    }

    /**
     * The conversation of the last 30 minutes (at least the newest 24 lines, at most about 6000 characters, newest kept) and the
     * last 30 other rows, in order. Identical consecutive other rows are condensed into one row with their count.
     */
    public static List<Timeline.Row> window(Timeline timeline, long now) {
        return window(timeline, now, TIMELINE_EVENTS, TIMELINE_CHATS, CHAT_WINDOW_MS, CHAT_CHARS);
    }

    /** As {@link #window(Timeline, long)} with a brain's sizes: other rows, newest chat lines kept, chat window, chat characters. */
    static List<Timeline.Row> window(Timeline timeline, long now, int events, int minChats, long chatWindowMs, int chatChars) {
        List<Timeline.Event> chats = new ArrayList<>();
        List<Timeline.Row> others = new ArrayList<>();
        for (Timeline.Event e : timeline.last(Timeline.KEEP)) {
            if (e.kind.startsWith("chat") || e.kind.equals("whisper_in")) {
                chats.add(e);
                continue;
            }
            Timeline.Row previous = others.isEmpty() ? null : others.getLast();
            if (previous != null && same(previous.event(), e)) others.set(others.size() - 1, new Timeline.Row(e, previous.count() + 1, previous.firstMs()));
            else others.add(new Timeline.Row(e));
        }
        TreeMap<Long, Timeline.Row> rows = new TreeMap<>();
        for (Timeline.Row row : others.subList(Math.max(0, others.size() - events), others.size())) rows.put(row.event().seq, row);
        int chars = 0;
        for (int i = chats.size() - 1; i >= 0; i--) {
            Timeline.Event e = chats.get(i);
            chars += Timeline.line(e).length();
            boolean wanted = chats.size() - i <= minChats || now - e.epochMs <= chatWindowMs;
            if (!wanted || chars > chatChars) break;
            rows.put(e.seq, new Timeline.Row(e));
        }
        return new ArrayList<>(rows.values());
    }

    private static boolean same(Timeline.Event a, Timeline.Event b) {
        return Objects.equals(a.kind, b.kind) && Objects.equals(a.text, b.text) && Objects.equals(a.outcome, b.outcome);
    }
}
