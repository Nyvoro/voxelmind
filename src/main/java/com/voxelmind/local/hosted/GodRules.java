package com.voxelmind.local.hosted;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The God's standing laws: when an event happens, the chosen commands run at once, every time,
 * without a decision. Bounded like the hosted mod: 8 rules, 20 fires per second, 500 fires per rule.
 */
public final class GodRules {
    public static final List<String> EVENTS = List.of("block_break", "entity_death", "player_death", "damage", "chat", "sleep", "dimension_change");
    private static final int MAX_RULES = 8;
    private static final int MAX_PER_SECOND = 20;
    private static final int MAX_LIFETIME = 500;

    public static final class Rule {
        public final String label;
        public final String when;
        public final String filter;
        public final List<String> then;
        public final long expiresAt;
        public int fires;
        int pendingFires;
        long secondStart;
        int secondFires;

        Rule(String label, String when, String filter, List<String> then, long expiresAt) {
            this.label = label;
            this.when = when;
            this.filter = filter;
            this.then = then;
            this.expiresAt = expiresAt;
        }
    }

    /** What happened, for matching and placeholders. */
    public record Event(String kind, @Nullable ServerPlayer player, ServerLevel level, Vec3 pos, String block, String entity, String text) {}

    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final String sourceName;

    public GodRules(String sourceName) {
        this.sourceName = sourceName;
    }

    public @Nullable String add(String label, String when, String filter, List<String> then, int ttlSeconds) {
        if (!EVENTS.contains(when)) return "unknown event " + when;
        if (then.isEmpty() || then.size() > 6) return "a rule needs 1 to 6 commands";
        for (String command : then) {
            String refused = CommandPolicy.refuse(command);
            if (refused != null) return refused;
        }
        String key = label.toLowerCase(Locale.ROOT);
        if (!rules.containsKey(key) && rules.size() >= MAX_RULES) return "at most " + MAX_RULES + " rules at once";
        rules.put(key, new Rule(label, when, filter == null ? "" : filter.toLowerCase(Locale.ROOT), List.copyOf(then),
                System.currentTimeMillis() + Math.max(5, Math.min(1800, ttlSeconds)) * 1000L));
        return null;
    }

    /** "all" clears every rule; returns how many were removed. */
    public int clear(String label) {
        if (label.equalsIgnoreCase("all")) {
            int n = rules.size();
            rules.clear();
            return n;
        }
        return rules.remove(label.toLowerCase(Locale.ROOT)) != null ? 1 : 0;
    }

    public List<Rule> all() {
        long now = System.currentTimeMillis();
        rules.values().removeIf(r -> r.expiresAt < now || r.fires >= MAX_LIFETIME);
        return new ArrayList<>(rules.values());
    }

    public void fire(MinecraftServer server, Event e, @Nullable ServerPlayer host) {
        long now = System.currentTimeMillis();
        Iterator<Rule> it = rules.values().iterator();
        while (it.hasNext()) {
            Rule r = it.next();
            if (r.expiresAt < now || r.fires >= MAX_LIFETIME) {
                it.remove();
                continue;
            }
            if (!r.when.equals(e.kind()) || !matches(r, e)) continue;
            if (now - r.secondStart > 1000) {
                r.secondStart = now;
                r.secondFires = 0;
            }
            if (++r.secondFires > MAX_PER_SECOND) continue;
            r.fires++;
            r.pendingFires++;
            for (String template : r.then) {
                String command = substitute(template, e, host);
                if (CommandPolicy.refuse(command) != null) continue;
                CommandRunner.runAt(server, e.level(), e.pos(), new Vec2(0, 0), sourceName, command);
            }
        }
    }

    /** Fire counts since the last call, for the timeline. */
    public Map<String, Integer> drainFires() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Rule r : rules.values()) {
            if (r.pendingFires > 0) {
                out.put(r.label, r.pendingFires);
                r.pendingFires = 0;
            }
        }
        return out;
    }

    private static boolean matches(Rule r, Event e) {
        if (r.filter.isEmpty() || r.filter.equals("*")) return true;
        return switch (e.kind()) {
            case "block_break" -> e.block().toLowerCase(Locale.ROOT).contains(r.filter);
            case "entity_death" -> e.entity().toLowerCase(Locale.ROOT).contains(r.filter);
            case "chat" -> e.text().toLowerCase(Locale.ROOT).contains(r.filter);
            default -> e.player() != null && e.player().getGameProfile().name().toLowerCase(Locale.ROOT).contains(r.filter);
        };
    }

    private static String substitute(String template, Event e, @Nullable ServerPlayer host) {
        String player = e.player() != null ? e.player().getGameProfile().name() : host != null ? host.getGameProfile().name() : "@p";
        BlockPos p = BlockPos.containing(e.pos());
        String text = e.text().replaceAll("[\"'{}\\[\\]\\\\]", "");
        return template.replace("{player}", player)
                .replace("{owner}", host != null ? host.getGameProfile().name() : player)
                .replace("{x}", Integer.toString(p.getX()))
                .replace("{y}", Integer.toString(p.getY()))
                .replace("{z}", Integer.toString(p.getZ()))
                .replace("{block}", e.block().isEmpty() ? "air" : e.block())
                .replace("{entity}", e.entity().isEmpty() ? "unknown" : e.entity())
                .replace("{text}", text);
    }
}
