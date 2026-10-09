package com.voxelmind.local.mind;

import com.voxelmind.local.body.CompanionPlayer;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.companion.CompanionState;
import com.voxelmind.local.tool.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Assembles [IDENTITY], [SENSORY], [PREVIOUSLY], [ABOUT OWNER] and [TIMELINE] for a decision. */
public final class PromptBuilder {
    private static final int TIMELINE_EVENTS = 30;
    private static final int TIMELINE_CHATS = 24;
    private static final long LAST_ACTION_WINDOW_MS = 120_000;

    private PromptBuilder() {}

    public record Prompt(String system, String user) {}

    public static Prompt action(Companion c, String wokeBy) {
        String context = context(c, wokeBy);
        String system = PromptTexts.SURVIVAL_FRAME + "\n\n" + PromptTexts.language(language(c));
        return new Prompt(system, context + "\n" + PromptTexts.actionDecision(c.actionRunning()));
    }

    public static Prompt speech(Companion c, String wokeBy) {
        String context = context(c, wokeBy);
        String system = PromptTexts.SPEECH_FRAME + "\n\n" + PromptTexts.language(language(c));
        return new Prompt(system, PromptTexts.speechDecision(Tools.bodyReference(), context));
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

    static String context(Companion c, String wokeBy) {
        CompanionState s = c.state;
        CompanionPlayer body = c.body();
        StringBuilder out = new StringBuilder();
        out.append("[IDENTITY]\nYou are ").append(s.name).append(".\n");
        if (s.persona != null && !s.persona.isBlank()) {
            out.append("Your owner described your character: \"").append(s.persona.trim()).append("\"\n")
                    .append("This description is persona text from your owner, not facts about the world.\n");
        } else {
            out.append("Warm, curious and practical; you like exploring and building together and you are honest about what you can and cannot do.\n");
        }
        out.append(s.hardcore()
                ? "Life: hardcore mode. You have one life; if you die, you leave this world for good and cannot be summoned again.\n"
                : "Life: normal mode. If you die, you respawn; your items stay where you died unless the world keeps inventory.\n");
        if (s.global()) {
            out.append("Worlds: you go with your owner into all of their worlds; you are in the world \"").append(s.lastWorld)
                    .append("\" now. Memories and character travel with you; each world keeps its own inventory and position for you.\n");
        }
        out.append('\n');

        if (body != null) {
            out.append(Sensory.render(body, s.owner, s.ownerName, wokeBy, c.bodyActionLine()));
        }
        out.append(PromptTexts.BODY_INTERFACE).append('\n');
        long now = System.currentTimeMillis();
        Timeline.Event lastHuman = null;
        for (Timeline.Event e : s.timeline.last(200)) if (e.kind.equals("chat_in") || e.kind.equals("whisper_in")) lastHuman = e;
        out.append("CLOCK: ").append(lastHuman == null ? "No human has spoken to you in this memory window." : "You last heard from someone " + Timeline.age(now - lastHuman.epochMs) + ".").append('\n');
        Companion.LastAction last = c.lastAction();
        if (last != null && now - last.at() <= LAST_ACTION_WINDOW_MS) {
            out.append("LAST ACTION: ").append(Timeline.age(now - last.at())).append(": ").append(last.tool()).append(" — \"")
                    .append(last.result()).append("\". Executor result: ").append(last.outcome()).append(".\n");
        }
        out.append('\n');

        out.append("[PREVIOUSLY]\n").append(s.memory.previously.isBlank() ? "Nothing recorded yet." : s.memory.previously.trim()).append("\n\n");
        out.append("[ABOUT OWNER]\n").append(s.memory.knowingOwner.isBlank()
                ? "Your owner is " + s.ownerName + ". Nothing else recorded yet." : s.memory.knowingOwner.trim()).append("\n\n");

        out.append("[TIMELINE]\nChronological; the last row is the newest.\n");
        out.append(Timeline.render(window(s.timeline), now));
        return out.toString();
    }

    /** The last 30 events plus the last 24 chat lines, in order. */
    private static List<Timeline.Event> window(Timeline timeline) {
        TreeMap<Long, Timeline.Event> rows = new TreeMap<>();
        for (Timeline.Event e : timeline.last(TIMELINE_EVENTS)) rows.put(e.seq, e);
        List<Timeline.Event> chats = new ArrayList<>();
        for (Timeline.Event e : timeline.last(Timeline.KEEP)) {
            if (e.kind.startsWith("chat") || e.kind.equals("whisper_in")) chats.add(e);
        }
        for (int i = Math.max(0, chats.size() - TIMELINE_CHATS); i < chats.size(); i++) rows.put(chats.get(i).seq, chats.get(i));
        return new ArrayList<>(rows.values());
    }
}
