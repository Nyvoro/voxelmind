package com.voxelmind.local.hosted;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.voxelmind.local.companion.Companion;
import com.voxelmind.local.compat.Compat;
import com.voxelmind.local.world.Names;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The bodiless game master: improvises adventures with commands, rules, sequences and creatures. */
public final class GodMind extends HostedMind {
    public static final String TAG = "voxelmind_god";
    private static final int ENTITY_BUDGET = 200;
    private static final int MAX_MOVES = 8;

    private final GodRules rules;
    private final Map<UUID, Move> moves = new LinkedHashMap<>();
    private int sequenceCounter;

    private static final class Move {
        final Mob mob;
        final Vec3 target;
        final double speed;
        final long deadline;
        long nextIssue;

        Move(Mob mob, Vec3 target, double speed, long deadline) {
            this.mob = mob;
            this.target = target;
            this.speed = speed;
            this.deadline = deadline;
        }
    }

    public GodMind(Companion c) {
        super(c);
        this.rules = new GodRules(c.name());
    }

    @Override
    protected String frame() {
        return HostedTexts.GOD_FRAME;
    }

    @Override
    protected String firstRow() {
        return "You were called into this world just now.";
    }

    @Override
    protected ChatFormatting nameColor() {
        return ChatFormatting.GOLD;
    }

    @Override
    protected long minGapMs() {
        return 1000;
    }

    @Override
    protected long idleMs() {
        return 120_000;
    }

    @Override
    protected boolean plainTextSpeaks() {
        return false;
    }

    @Override
    protected List<ServerPlayer> audience() {
        if (c.state.guests) return humans();
        ServerPlayer host = host();
        return host == null ? List.of() : List.of(host);
    }

    @Override
    protected @Nullable String onStart(ServerPlayer owner) {
        return null;
    }

    @Override
    protected void onStop(String reason) {
        String cleaned = cleanup();
        String reset = resets.restore(server(), humans());
        for (ServerPlayer p : humans()) {
            p.sendSystemMessage(Component.literal("[VoxelMind] " + c.name() + " has left (" + reason + "). Rules, effects and summoned creatures are cleared."
                    + (reset.isEmpty() ? "" : " Reset: " + reset + ".")).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        c.event("note", "cleanup at the end: " + cleaned, false, null);
    }

    private long lastFireReport;

    @Override
    protected void tickWorld(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Move>> it = moves.entrySet().iterator();
        long now = server.getTickCount();
        while (it.hasNext()) {
            Move m = it.next().getValue();
            String end = null;
            boolean ok = false;
            if (!m.mob.isAlive() || m.mob.isRemoved()) end = "the creature died or unloaded";
            else if (m.mob.position().distanceTo(m.target) <= 1.5) {
                end = "reached";
                ok = true;
            } else if (now >= m.deadline) end = "timed out";
            else if (now >= m.nextIssue) {
                m.mob.getNavigation().moveTo(m.target.x, m.target.y, m.target.z, m.speed);
                m.nextIssue = now + 10;
            }
            if (end != null) {
                it.remove();
                report((ok ? "result of \"move_entity\": " : "FAILED \"move_entity\": ") + end + "; " + m.mob.getUUID() + " at " + fmt(m.mob.position())
                        + ", target " + fmt(m.target) + ", distance " + String.format(Locale.ROOT, "%.1f", m.mob.position().distanceTo(m.target)), ok);
            }
        }
        // Condensed: the counts of the last 5 seconds in one row per rule (a rule may fire every tick).
        if (now - lastFireReport >= 100) {
            lastFireReport = now;
            Map<String, Integer> fired = rules.drainFires();
            fired.forEach((label, count) -> c.event("event", "rule \"" + label + "\" fired " + count + "×", false, null));
        }
    }

    /** Whether this God observes the player: the host always, guests when the owner allows it. */
    public boolean sees(ServerPlayer p) {
        return c.state.guests || p.getUUID().equals(c.state.owner);
    }

    public void fireRules(GodRules.Event event) {
        if (active()) rules.fire(server(), event, host());
    }

    // ---- facts ----

    @Override
    protected String facts(String wokeBy, long now) {
        MinecraftServer server = server();
        ServerPlayer host = host();
        ServerLevel level = host != null ? Compat.level(host) : server.overworld();
        List<String> blocks = new ArrayList<>();
        blocks.add("[IDENTITY]\nThe players call you " + c.name() + ". " + persona(HostedTexts.GOD_PERSONA)
                + "\nEmbodiment: this God session has no player body or physical position. Commands originate at the host; summoned creatures have their own UUIDs and positions.");
        HostedTexts.Campaign campaign = HostedTexts.CAMPAIGNS.getOrDefault(c.state.campaign, HostedTexts.CAMPAIGNS.get("sandbox"));
        blocks.add("[CAMPAIGN]\n" + campaign.label() + ": " + campaign.text());
        List<String> ruleText = new ArrayList<>();
        for (GodRules.Rule r : rules.all()) {
            ruleText.add("\"" + r.label + "\" on " + r.when + " (" + r.fires + "× fired, " + Math.max(0, (r.expiresAt - now) / 1000) + "s left)");
        }
        blocks.add(HostedFacts.world(server, level) + "\nYour rules in force: " + (ruleText.isEmpty() ? "none" : String.join("; ", ruleText))
                + ". Sequences running: " + scheduler.running() + ". Loaded entities tagged " + TAG + ": " + tagged().size() + ".");
        blocks.add(HostedTexts.commandReference(HostedFacts.version()));
        List<ServerPlayer> seen = audience();
        if (seen.isEmpty()) blocks.add("[PLAYERS]\nNobody is in the world right now.");
        else {
            StringBuilder players = new StringBuilder("[PLAYERS]");
            for (ServerPlayer p : seen) players.append('\n').append(HostedFacts.player(p, p == host));
            blocks.add(players.toString());
        }
        blocks.add(entities(host));
        blocks.add(HostedFacts.timeline(c.state.timeline.last(200), now));
        blocks.add(previously());
        blocks.add(about("ABOUT PLAYER"));
        blocks.add("[SETTINGS]\nCampaign frame: " + c.state.campaign + ".\nThe players call you " + c.name() + ". Chaos level " + c.state.chaos + "/5 ("
                + HostedTexts.chaosText(c.state.chaos) + "). Guests on this world are " + (c.state.guests ? "visible to you" : "hidden from you") + ".");
        blocks.add(execution());
        blocks.add(turn(wokeBy, now));
        return String.join("\n\n", blocks);
    }

    private List<Entity> tagged() {
        List<Entity> out = new ArrayList<>();
        for (ServerLevel l : server().getAllLevels()) out.addAll(l.getEntities(EntityTypeTest.forClass(Entity.class), e -> e.entityTags().contains(TAG)));
        return out;
    }

    private String entities(@Nullable ServerPlayer host) {
        List<Entity> all = new ArrayList<>(tagged());
        all.removeIf(e -> !e.isAlive());
        if (host != null) all.sort((a, b) -> Double.compare(a.distanceToSqr(host), b.distanceToSqr(host)));
        StringBuilder out = new StringBuilder("[YOUR ENTITIES]\nAlive entities tagged " + TAG + " currently loaded: " + all.size() + "; shown "
                + Math.min(24, all.size()) + ", nearest to the host first. Unloaded entities are outside this observation.");
        for (Entity e : all.subList(0, Math.min(24, all.size()))) {
            out.append('\n').append(e.getUUID()).append(": ").append(Names.id(e.getType()));
            if (e.getCustomName() != null) out.append(" named \"").append(e.getCustomName().getString()).append('"');
            out.append(", at ").append(fmt(e.position())).append(" (").append(HostedFacts.dim((ServerLevel) e.level())).append(")");
            if (e instanceof LivingEntity l) out.append("; HP ").append(Math.round(l.getHealth()));
            out.append("; onGround=").append(e.onGround());
            if (e instanceof Mob m) {
                out.append("; NoAI=").append(m.isNoAi()).append("; navigation ").append(m.getNavigation().isDone() ? "idle" : "following path");
                LivingEntity target = m.getTarget();
                out.append("; current attack target: ").append(target == null ? "none" : target.getUUID() + " " + Names.id(target.getType()));
            }
            if (e instanceof TamableAnimal t) out.append("; tamed=").append(t.isTame());
            Move move = moves.get(e.getUUID());
            if (move != null) out.append("; moving to ").append(fmt(move.target));
        }
        return out.toString();
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
    }

    // ---- tools ----

    @Override
    protected JsonArray tools() {
        return HostedTools.god();
    }

    @Override
    protected @Nullable Outcome tool(String name, JsonObject args) {
        return switch (name) {
            case "registry" -> registry(args);
            case "summon_entity" -> summonEntity(args, true);
            case "name_entity" -> nameEntity(args);
            case "say" -> speak(str(args, "text"), str(args, "channel"), str(args, "subtitle"));
            case "run" -> run(str(args, "command"));
            case "move_entity" -> move(args);
            case "sequence" -> sequence(args);
            case "rule" -> rule(args);
            case "clear_rule" -> {
                int n = rules.clear(str(args, "label"));
                yield new Outcome("result of \"clear_rule\": cleared " + n + " rule(s)", true, false);
            }
            case "rain" -> rain(args);
            case "cleanup" -> new Outcome("result of \"cleanup\": " + cleanup(), true, true);
            case "observe" -> observe(args);
            case "note" -> note(args);
            default -> new Outcome("refused " + name + ": no such tool", false, false);
        };
    }

    private Outcome sequence(JsonObject args) {
        if (!args.has("steps") || !args.get("steps").isJsonArray()) return new Outcome("refused sequence: steps are missing", false, false);
        List<Scheduler.Step> steps = new ArrayList<>();
        int total = 0;
        for (JsonElement el : args.getAsJsonArray("steps")) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();
            int delay = (int) Math.max(0, Math.floor(num(s, "delay", 0)));
            String command = str(s, "command");
            String refused = CommandPolicy.refuse(command);
            if (refused != null) return new Outcome("refused sequence: step \"" + Scheduler.truncate(command, 120) + "\": " + refused, false, false);
            total += delay;
            steps.add(new Scheduler.Step(delay, command));
        }
        if (steps.isEmpty() || steps.size() > 40) return new Outcome("refused sequence: 1 to 40 steps", false, false);
        if (total > 2400) return new Outcome("refused sequence: longer than 2400 ticks in total", false, false);
        String label = str(args, "label").isBlank() ? "sequence " + (++sequenceCounter) : str(args, "label");
        for (Scheduler.Step s : steps) resets.before(server(), s.command());
        Scheduler.Run run = scheduler.start("s" + sequenceCounter, label, steps, null,
                done -> report("result of sequence " + Scheduler.summary(done) + Scheduler.stepLines(done), done.results().stream().allMatch(Scheduler.StepResult::ok)));
        if (run == null) return new Outcome("refused sequence: " + Scheduler.MAX_RUNNING + " sequences are already running", false, false);
        c.event("note", "you issued sequence \"" + label + "\" (" + steps.size() + " steps, " + total + " ticks; pending server feedback)", false, null);
        return null;
    }

    private Outcome rule(JsonObject args) {
        String label = str(args, "label");
        if (!label.matches("^[A-Za-z0-9 _.,'!?-]{1,40}$")) return new Outcome("refused rule: label must be 1-40 plain characters", false, false);
        List<String> then = new ArrayList<>();
        if (args.has("then") && args.get("then").isJsonArray()) for (JsonElement e : args.getAsJsonArray("then")) then.add(e.getAsString());
        int ttl = (int) num(args, "ttl_seconds", 300);
        String error = rules.add(label, str(args, "when"), str(args, "filter"), then, ttl);
        if (error != null) return new Outcome("refused rule \"" + label + "\": " + error, false, false);
        for (String command : then) resets.before(server(), command);
        return new Outcome("result of \"rule " + label + "\": in force on " + str(args, "when") + " for " + Math.max(5, Math.min(1800, ttl)) + "s", true, false);
    }

    private Outcome rain(JsonObject args) {
        ServerPlayer host = host();
        if (host == null) return new Outcome("FAILED \"rain\": the host is not in the world", false, true);
        String entity = str(args, "entity").toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (!entity.matches("^[a-z0-9_]{2,40}$") || entity.equals("player") || entity.equals("ender_dragon") || entity.equals("wither")) {
            return new Outcome("refused rain: \"" + entity + "\" cannot rain", false, false);
        }
        int count = (int) Math.max(1, Math.min(64, num(args, "count", 8)));
        double radius = Math.max(2, Math.min(24, num(args, "radius", 6)));
        boolean gentle = !args.has("gentle") || args.get("gentle").getAsBoolean();
        String name = str(args, "name");
        if (name.matches(".*[\"\\\\{}\\[\\]].*")) return new Outcome("refused rain: the name may not contain quotes or brackets", false, false);
        int budget = ENTITY_BUDGET - tagged().size();
        int planned = Math.min(count, Math.max(0, budget));
        int summoned = 0;
        int failed = 0;
        for (int i = 0; i < planned; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double r = ThreadLocalRandom.current().nextDouble() * radius;
            double x = host.getX() + Math.cos(angle) * r;
            double z = host.getZ() + Math.sin(angle) * r;
            double y = host.getY() + 18 + ThreadLocalRandom.current().nextDouble() * 8;
            String nbt = "{Tags:[\"" + TAG + "\"]" + (gentle ? ",active_effects:[{id:\"minecraft:slow_falling\",duration:600,amplifier:0b,show_particles:0b}]" : "")
                    + (name.isBlank() ? "" : ",CustomName:{text:\"" + name + "\"},CustomNameVisible:1b") + "}";
            CommandRunner.Result res = CommandRunner.runAtHost(host, c.name(), String.format(Locale.ROOT, "summon minecraft:%s %.2f %.2f %.2f %s", entity, x, y, z, nbt));
            if (res.ok()) summoned++;
            else if (++failed >= 2 && summoned == 0) return new Outcome("FAILED \"rain\": " + res.text(), false, true);
        }
        return new Outcome("result of \"rain\": " + summoned + " " + entity + " falling from the sky" + (planned < count ? " (budget capped)" : ""), summoned > 0, true);
    }

    private Outcome move(JsonObject args) {
        String uuidText = str(args, "entity_uuid");
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            return new Outcome("refused move_entity: entity_uuid must be a full UUID", false, false);
        }
        if (!args.has("target") || !args.get("target").isJsonObject()) return new Outcome("refused move_entity: target x/y/z missing", false, false);
        JsonObject t = args.getAsJsonObject("target");
        Vec3 target = new Vec3(num(t, "x", 0), num(t, "y", 0), num(t, "z", 0));
        Mob mob = null;
        for (ServerLevel l : server().getAllLevels()) {
            Entity e = l.getEntity(uuid);
            if (e instanceof Mob m) mob = m;
        }
        if (mob == null || !mob.isAlive()) return new Outcome("FAILED \"move_entity\": no loaded creature " + uuid, false, true);
        if (mob.isNoAi()) return new Outcome("FAILED \"move_entity\": the creature has NoAI", false, true);
        if (mob.isPassenger()) return new Outcome("FAILED \"move_entity\": the creature is riding", false, true);
        if (mob.position().distanceTo(target) > 64) return new Outcome("FAILED \"move_entity\": target farther than 64 blocks", false, true);
        if (mob.position().distanceTo(target) <= 1.5) return new Outcome("result of \"move_entity\": already within 1.5 blocks", true, true);
        if (!moves.containsKey(uuid) && moves.size() >= MAX_MOVES) return new Outcome("refused move_entity: 8 moves are already running", false, false);
        double speed = Math.max(0.1, Math.min(2, num(args, "speed", 1)));
        if (!mob.getNavigation().moveTo(target.x, target.y, target.z, speed)) {
            return new Outcome("FAILED \"move_entity\": Minecraft found no complete path", false, true);
        }
        long seconds = (long) Math.max(1, Math.min(30, num(args, "timeout_seconds", 20)));
        long now = server().getTickCount();
        Move move = new Move(mob, target, speed, now + seconds * 20);
        move.nextIssue = now + 10;
        moves.put(uuid, move);
        return null;
    }

    /** Removes tagged creatures, cancels sequences and moves, clears rules. */
    private String cleanup() {
        moves.clear();
        int removed = 0;
        for (Entity e : tagged()) {
            e.discard();
            removed++;
        }
        scheduler.cancelAll();
        int cleared = rules.clear("all");
        return "removed " + removed + " creatures, cancelled sequences, cleared " + cleared + " rules";
    }
}
