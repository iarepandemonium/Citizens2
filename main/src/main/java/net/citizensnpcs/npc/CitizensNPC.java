package net.citizensnpcs.npc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scoreboard.Team;
import org.bukkit.scoreboard.Team.Option;
import org.bukkit.scoreboard.Team.OptionStatus;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

import net.citizensnpcs.NPCNeedsRespawnEvent;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.AbstractNPC;
import net.citizensnpcs.api.npc.BlockBreaker;
import net.citizensnpcs.api.npc.BlockBreaker.BlockBreakerConfiguration;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.npc.ai.CitizensNavigator;
import net.citizensnpcs.npc.skin.SkinnableEntity;
import net.citizensnpcs.trait.CurrentLocation;
import net.citizensnpcs.util.ChunkCoord;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.NMS;
import net.citizensnpcs.util.Util;

public class CitizensNPC extends AbstractNPC {
    private ChunkCoord cachedCoord;
    private EntityController entityController;
    private final CitizensNavigator navigator = new CitizensNavigator(this);
    private int updateCounter = 0;

    public CitizensNPC(UUID uuid, int id, String name, EntityController entityController, NPCRegistry registry) {
        super(uuid, id, name, registry);
        Preconditions.checkNotNull(entityController);
        this.entityController = entityController;
    }

    @Override
    public boolean despawn(DespawnReason reason) {
        if (!isSpawned() && reason != DespawnReason.DEATH) {
            Messaging.debug("Tried to despawn", getId(), "while already despawned.");
            if (reason == DespawnReason.REMOVAL) {
                Bukkit.getPluginManager().callEvent(new NPCDespawnEvent(this, reason));
            }
            if (reason == DespawnReason.RELOAD) {
                unloadEvents();
            }
            return false;
        }
        NPCDespawnEvent event = new NPCDespawnEvent(this, reason);
        if (reason == DespawnReason.CHUNK_UNLOAD) {
            event.setCancelled(Setting.KEEP_CHUNKS_LOADED.asBoolean());
        }
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() && reason != DespawnReason.DEATH) {
            Messaging.debug("Couldn't despawn", getId(), "due to despawn event cancellation. Will load chunk.",
                    getEntity().isValid());
            return false;
        }
        boolean keepSelected = getTrait(Spawned.class).shouldSpawn();
        if (!keepSelected) {
            data().remove("selectors");
        }
        navigator.onDespawn();
        if (reason == DespawnReason.RELOAD) {
            unloadEvents();
        }
        for (Trait trait : new ArrayList<Trait>(traits.values())) {
            trait.onDespawn();
        }
        Messaging.debug("Despawned", getId(), "DespawnReason.", reason);
        if (reason == DespawnReason.DEATH) {
            entityController.setEntity(null);
        } else {
            entityController.remove();
        }
        return true;
    }

    @Override
    public void destroy() {
        super.destroy();
        resetCachedCoord();
    }

    @Override
    public void faceLocation(Location location) {
        if (!isSpawned())
            return;
        Util.faceLocation(getEntity(), location);
    }

    @Override
    public BlockBreaker getBlockBreaker(Block targetBlock, BlockBreakerConfiguration config) {
        return NMS.getBlockBreaker(getEntity(), targetBlock, config);
    }

    @Override
    public Entity getEntity() {
        return entityController == null ? null : entityController.getBukkitEntity();
    }

    @Override
    public Navigator getNavigator() {
        return navigator;
    }

    @Override
    public Location getStoredLocation() {
        return isSpawned() ? getEntity().getLocation() : getTrait(CurrentLocation.class).getLocation();
    }

    @Override
    public boolean isFlyable() {
        updateFlyableState();
        return super.isFlyable();
    }

    @Override
    public void load(final DataKey root) {
        super.load(root);

        // Spawn the NPC
        CurrentLocation spawnLocation = getTrait(CurrentLocation.class);
        if (getTrait(Spawned.class).shouldSpawn() && spawnLocation.getLocation() != null) {
            spawn(spawnLocation.getLocation(), SpawnReason.RESPAWN);
        }
        if (getTrait(Spawned.class).shouldSpawn() && spawnLocation.getLocation() == null) {
            Messaging.debug("Tried to spawn", getId(), "on load but world was null");
        }

        navigator.load(root.getRelative("navigator"));
    }

    private void resetCachedCoord() {
        if (cachedCoord != null) {
            CHUNK_LOADERS.remove(NPC_METADATA_MARKER, CHUNK_LOADERS);
            CHUNK_LOADERS.remove(cachedCoord, this);
            if (CHUNK_LOADERS.get(cachedCoord).size() == 0) {
                cachedCoord.setForceLoaded(false);
            }
            cachedCoord = null;
        }
    }

    @Override
    public void save(DataKey root) {
        super.save(root);
        if (!data().get(NPC.SHOULD_SAVE_METADATA, true))
            return;
        navigator.save(root.getRelative("navigator"));
    }

    @Override
    public void setBukkitEntityType(EntityType type) {
        EntityController controller = EntityControllers.createForType(type);
        if (controller == null)
            throw new IllegalArgumentException("Unsupported entity type " + type);
        setEntityController(controller);
    }

    public void setEntityController(EntityController newController) {
        Preconditions.checkNotNull(newController);
        boolean wasSpawned = isSpawned();
        Location prev = null;
        if (wasSpawned) {
            prev = getEntity().getLocation();
            despawn(DespawnReason.PENDING_RESPAWN);
        }
        entityController = newController;
        if (wasSpawned) {
            spawn(prev, SpawnReason.RESPAWN);
        }
    }

    @Override
    public void setFlyable(boolean flyable) {
        super.setFlyable(flyable);
        updateFlyableState();
    }

    @Override
    public boolean spawn(Location at) {
        return spawn(at, SpawnReason.PLUGIN);
    }

    @Override
    public boolean spawn(Location at, SpawnReason reason) {
        Preconditions.checkNotNull(at, "location cannot be null");
        Preconditions.checkNotNull(reason, "reason cannot be null");
        if (isSpawned()) {
            Messaging.debug("Tried to spawn", getId(), "while already spawned.");
            return false;
        }
        if (at.getWorld() == null) {
            Messaging.debug("Tried to spawn", getId(), "but the world was null.");
            return false;
        }
        data().get(NPC.DEFAULT_PROTECTED_METADATA, true);

        at = at.clone();

        getTrait(CurrentLocation.class).setLocation(at);
        entityController.spawn(at, this);

        getEntity().setMetadata(NPC_METADATA_MARKER, new FixedMetadataValue(CitizensAPI.getPlugin(), true));

        boolean couldSpawn = !Util.isLoaded(at) ? false
                : NMS.addEntityToWorld(getEntity(), CreatureSpawnEvent.SpawnReason.CUSTOM);

        // send skin packets, if applicable, before other NMS packets are sent
        if (couldSpawn) {
            SkinnableEntity skinnable = getEntity() instanceof SkinnableEntity ? ((SkinnableEntity) getEntity()) : null;
            if (skinnable != null) {
                skinnable.getSkinTracker().onSpawnNPC();
            }
        }

        if (!couldSpawn) {
            Messaging.debug("Retrying spawn of", getId(), "later due to chunk being unloaded.",
                    Util.isLoaded(at) ? "Util.isLoaded true" : "Util.isLoaded false");
            // we need to wait for a chunk load before trying to spawn
            entityController.remove();
            Bukkit.getPluginManager().callEvent(new NPCNeedsRespawnEvent(this, at));
            return false;
        }

        getEntity().teleport(at);

        NMS.setHeadYaw(getEntity(), at.getYaw());

        // Set the spawned state
        getTrait(CurrentLocation.class).setLocation(at);
        getTrait(Spawned.class).setSpawned(true);

        NPCSpawnEvent spawnEvent = new NPCSpawnEvent(this, at, reason);
        Bukkit.getPluginManager().callEvent(spawnEvent);

        if (spawnEvent.isCancelled()) {
            entityController.remove();
            Messaging.debug("Couldn't spawn", getId(), "due to event cancellation.");
            return false;
        }

        navigator.onSpawn();

        // Modify NPC using traits after the entity has been created
        Collection<Trait> onSpawn = traits.values();

        // work around traits modifying the map during this iteration.
        for (Trait trait : onSpawn.toArray(new Trait[onSpawn.size()])) {
            try {
                trait.onSpawn();
            } catch (Throwable ex) {
                Messaging.severeTr(Messages.TRAIT_ONSPAWN_FAILED, trait.getName(), getId());
                ex.printStackTrace();
            }
        }

        if (getEntity() instanceof LivingEntity) {
            LivingEntity entity = (LivingEntity) getEntity();
            entity.setRemoveWhenFarAway(false);

            if (NMS.getStepHeight(entity) < 1) {
                NMS.setStepHeight(entity, 1);
            }
            if (getEntity() instanceof Player) {
                NMS.replaceTrackerEntry((Player) getEntity());
            }
        }

        return true;
    }

    @Override
    public void update() {
        try {
            super.update();
            if (!isSpawned()) {
                resetCachedCoord();
                return;
            }
            if (data().get(NPC.SWIMMING_METADATA, true)) {
                NMS.trySwim(getEntity());
            }
            navigator.run();
            if (SUPPORT_GLOWING) {
                try {
                    getEntity().setGlowing(data().get(NPC.GLOWING_METADATA, false));
                } catch (NoSuchMethodError e) {
                    SUPPORT_GLOWING = false;
                }
            }

            if (!getNavigator().isNavigating() && updateCounter++ > Setting.PACKET_UPDATE_DELAY.asInt()) {
                if (Setting.KEEP_CHUNKS_LOADED.asBoolean()) {
                    ChunkCoord currentCoord = new ChunkCoord(getStoredLocation());
                    if (!currentCoord.equals(cachedCoord)) {
                        resetCachedCoord();
                        currentCoord.setForceLoaded(true);
                        CHUNK_LOADERS.put(currentCoord, this);
                        cachedCoord = currentCoord;
                    }
                }
                updateCounter = 0;

                Player player = getEntity().getType() == EntityType.PLAYER ? (Player) getEntity() : null;
                NMS.sendPositionUpdate(player, getEntity(), getStoredLocation());
            }

            if (getEntity() instanceof LivingEntity) {
                if (updateCounter == 0) {
                    updateCustomName();
                }
                String nameplateVisible = data().<Object> get(NPC.NAMEPLATE_VISIBLE_METADATA, true).toString();
                if (nameplateVisible.equals("hover")) {
                    ((LivingEntity) getEntity()).setCustomNameVisible(false);
                } else {
                    ((LivingEntity) getEntity()).setCustomNameVisible(Boolean.parseBoolean(nameplateVisible));
                }
                if (data().get(NPC.DEFAULT_PROTECTED_METADATA, true)) {
                    NMS.setKnockbackResistance((LivingEntity) getEntity(), 1D);
                } else {
                    NMS.setKnockbackResistance((LivingEntity) getEntity(), 0D);
                }
            }

            if (SUPPORT_SILENT && data().has(NPC.SILENT_METADATA)) {
                try {
                    getEntity().setSilent(Boolean.parseBoolean(data().get(NPC.SILENT_METADATA).toString()));
                } catch (NoSuchMethodError e) {
                    SUPPORT_SILENT = false;
                }
            }
        } catch (Exception ex) {
            Throwable error = Throwables.getRootCause(ex);
            Messaging.logTr(Messages.EXCEPTION_UPDATING_NPC, getId(), error.getMessage());
            error.printStackTrace();
        }
    }

    private void updateCustomName() {
        boolean nameVisibility = false;

        if (!getEntity().isCustomNameVisible()
                && !data().<Object> get(NPC.NAMEPLATE_VISIBLE_METADATA, true).toString().equals("hover")) {
            getEntity().setCustomName("");
        } else {
            nameVisibility = true;
            getEntity().setCustomName(getFullName());
        }

        String teamName = data().get(NPC.SCOREBOARD_FAKE_TEAM_NAME_METADATA, "");
        Team team = null;
        if (!(getEntity() instanceof Player) || teamName.length() == 0
                || (team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName)) == null)
            return;

        if (!Setting.USE_SCOREBOARD_TEAMS.asBoolean()) {
            team.unregister();
            data().remove(NPC.SCOREBOARD_FAKE_TEAM_NAME_METADATA);
            return;
        }

        if (SUPPORT_TEAM_SETOPTION) {
            try {
                team.setOption(Option.NAME_TAG_VISIBILITY, nameVisibility ? OptionStatus.ALWAYS : OptionStatus.NEVER);
            } catch (NoSuchMethodError e) {
                SUPPORT_TEAM_SETOPTION = false;
            } catch (NoClassDefFoundError e) {
                SUPPORT_TEAM_SETOPTION = false;
            }
        }

        if (data().has(NPC.GLOWING_COLOR_METADATA)) {
            if (team.getPrefix() == null || team.getPrefix().length() == 0 || (data().has("previous-glowing-color")
                    && !team.getPrefix().equals(data().get("previous-glowing-color")))) {
                team.setPrefix(ChatColor.valueOf(data().<String> get(NPC.GLOWING_COLOR_METADATA)).toString());
                data().set("previous-glowing-color", team.getPrefix());
            }
        }
    }

    private void updateFlyableState() {
        EntityType type = isSpawned() ? getEntity().getType() : getTrait(MobType.class).getType();
        if (type == null)
            return;
        if (Util.isAlwaysFlyable(type)) {
            data().setPersistent(NPC.FLYABLE_METADATA, true);
        }
    }

    private static final SetMultimap<ChunkCoord, NPC> CHUNK_LOADERS = HashMultimap.create();
    private static final String NPC_METADATA_MARKER = "NPC";
    private static boolean SUPPORT_GLOWING = true;
    private static boolean SUPPORT_SILENT = true;
    private static boolean SUPPORT_TEAM_SETOPTION = true;
}
