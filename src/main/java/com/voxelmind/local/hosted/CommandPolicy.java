package com.voxelmind.local.hosted;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hosted minds' command boundary, unchanged from the hosted brain and mod: an allowlist of verbs,
 * admin verbs refused by name, and harmful verbs that may not hit every player. A technical safety
 * limit on console authority, not a judgement about what the mind wants to do.
 */
public final class CommandPolicy {
    public static final Set<String> ALLOWED = Set.of("summon", "fill", "setblock", "clone", "place", "fillbiome", "give", "clear",
            "item", "effect", "attribute", "tp", "teleport", "spreadplayers", "time", "weather", "tick", "gamerule", "difficulty",
            "title", "tellraw", "bossbar", "scoreboard", "team", "playsound", "stopsound", "particle", "ride", "damage", "experience",
            "xp", "gamemode", "spectate", "locate", "kill", "schedule", "function", "data", "enchant", "spawnpoint", "tag", "trigger",
            "advancement", "recipe", "loot", "worldborder", "random", "execute");
    public static final Set<String> FORBIDDEN = Set.of("op", "deop", "whitelist", "ban", "ban-ip", "banlist", "pardon", "pardon-ip",
            "kick", "stop", "save-all", "save-off", "save-on", "reload", "publish", "debug", "jfr", "perf", "setworldspawn",
            "defaultgamemode", "datapack", "transfer", "me", "msg", "tell", "w", "teammsg", "tm", "say", "setidletimeout");
    public static final Set<String> HARMFUL = Set.of("kill", "clear", "gamemode", "tp", "teleport", "damage", "spreadplayers",
            "attribute", "spectate", "item", "ride", "effect");

    private static final Pattern HEAD = Pattern.compile("^([a-z][a-z0-9_-]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUN = Pattern.compile("\\brun\\s+([a-z][a-z0-9_-]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVERY_PLAYER = Pattern.compile("@a\\b|@e\\[[^\\]]*type\\s*=\\s*(minecraft:)?player", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY_SELECTOR = Pattern.compile("@e(\\[[^\\]]*\\])?", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSITIVE_TYPE = Pattern.compile("type\\s*=\\s*(?!!)\\S", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_PLAYER = Pattern.compile("type\\s*=\\s*!(minecraft:)?player", Pattern.CASE_INSENSITIVE);

    private CommandPolicy() {}

    /** Collapses whitespace and strips one leading slash, as the hosted channel did. */
    public static String normalize(String command) {
        String s = command.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        return s.startsWith("/") ? s.substring(1).trim() : s;
    }

    /** Why the command may not run, or null when it may. */
    public static @Nullable String refuse(String command) {
        String s = normalize(command);
        if (s.isEmpty()) return "empty command";
        if (s.length() > 1000) return "command longer than 1000 chars";
        List<String> verbs = verbs(s);
        if (verbs.isEmpty()) return "no command verb";
        for (String v : verbs) {
            if (FORBIDDEN.contains(v)) return "\"" + v + "\" is a server-admin command and is never available here";
            if (!ALLOWED.contains(v)) return "\"" + v + "\" is not in the command set";
        }
        if (verbs.getFirst().equals("execute") && verbs.size() == 1) return "execute needs a \"run <command>\" part";
        boolean harmful = verbs.stream().anyMatch(HARMFUL::contains);
        if (harmful) {
            if (EVERY_PLAYER.matcher(s).find()) return "harmful commands may not target every player (@a) — name the player or use @p";
            if (verbs.contains("kill") || verbs.contains("damage")) {
                Matcher m = ENTITY_SELECTOR.matcher(s);
                while (m.find()) {
                    String args = m.group(1) == null ? "" : m.group(1);
                    if (!POSITIVE_TYPE.matcher(args).find() && !NOT_PLAYER.matcher(args).find()) {
                        return "\"kill\" with @e needs a type= filter, otherwise it hits players too";
                    }
                }
            }
        }
        return null;
    }

    public static List<String> verbs(String normalized) {
        List<String> out = new ArrayList<>();
        Matcher head = HEAD.matcher(normalized);
        if (head.find()) out.add(head.group(1).toLowerCase(Locale.ROOT));
        Matcher run = RUN.matcher(normalized);
        while (run.find()) out.add(run.group(1).toLowerCase(Locale.ROOT));
        return out;
    }
}
