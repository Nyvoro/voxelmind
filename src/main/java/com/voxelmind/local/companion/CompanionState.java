package com.voxelmind.local.companion;

import com.voxelmind.local.mind.Timeline;

import java.util.UUID;

/** Everything about one companion that outlives a session, stored as JSON in the world folder. */
public final class CompanionState {
    public String name;
    public UUID id;
    public UUID owner;
    public String ownerName;
    /** The owner's description of the character, free text. */
    public String persona = "";
    /** Present in the world while its owner is online. */
    public boolean active = true;
    /** "normal": respawns after death. "hardcore": one life; after death it never returns. */
    public String mode = "normal";
    /** "companion" (a player body that plays), "builder" (the fairy with console power) or "god" (bodiless game master). */
    public String kind = "companion";
    /** God: campaign frame sandbox|trials|living|chaos, chaos level 1-5, whether guests are seen. */
    public String campaign = "sandbox";
    public int chaos = 3;
    public boolean guests = true;
    /** Builder: the creature it appears as. */
    public String bodyType = "allay";

    /** "world": lives in this world only. "global": goes with its owner into every world of this game. */
    public String scope = "world";
    /** Name of the world it was last in, so a global companion notices a change of world. */
    public String lastWorld = "";
    /** When it died for good in hardcore mode; 0 while alive. */
    public long fallenAt;
    public String fallenBy = "";
    public long createdAt;
    /** Borrowed skin (signed textures property from Mojang), or empty for the default skin. */
    public String skinSource = "";
    public String skinValue = "";
    public String skinSignature = "";

    public Timeline timeline = new Timeline();
    public Memory memory = new Memory();

    public static final class Memory {
        public String previously = "";
        public String knowingOwner = "";
        /** Last timeline sequence number folded into the texts above. */
        public long cursor;
        public long updatedAt;
    }

    public boolean hosted() {
        return "builder".equals(kind) || "god".equals(kind);
    }

    public boolean hardcore() {
        return "hardcore".equals(mode);
    }

    public boolean global() {
        return "global".equals(scope);
    }

    public boolean fallen() {
        return fallenAt > 0;
    }

    public static UUID idFor(UUID owner, String name) {
        return UUID.nameUUIDFromBytes(("voxelmind-local:" + owner + ":" + name.toLowerCase()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
