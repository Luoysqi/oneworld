package com.oneworld;

import com.mojang.authlib.GameProfile;
import com.oneworld.defense.HeatZoneHandler;
import com.oneworld.defense.OneWorldAttachments;
import com.oneworld.defense.SkyDefenseHandler;
import com.oneworld.dragon.DragonLiteHandler;
import com.oneworld.mixin.ChunkAccessHeightmaps;
import com.oneworld.portal.PortalLogic;
import com.oneworld.worldgen.BandHeights;
import com.oneworld.worldgen.MergedChunkGenerator;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AfterDamage;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AfterDeath;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AllowDamage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.Load;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarting;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopped;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Disconnect;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature.EndSpike;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OneWorld implements ModInitializer {
   public static final String MOD_ID = "oneworld";
   public static final Logger LOGGER = LoggerFactory.getLogger("oneworld");
   public static final boolean DEBUG_ENTITIES = System.getProperty("oneworld.debugEntities") != null || System.getenv("ONEWORLD_DEBUG_ENTITIES") != null;
   public static final int NETHER_MIN_Y = -1008;
   public static final int NETHER_MAX_Y = -881;
   public static final int NETHER_Y_OFFSET = -1008;
   public static final int NETHER_GAP_MIN_Y = -880;
   public static final int NETHER_GAP_MAX_Y = -193;
   public static final int HEAT_MIN_Y = -192;
   public static final int HEAT_MAX_Y = -65;
   public static final int OVERWORLD_MIN_Y = -64;
   public static final int OVERWORLD_MAX_Y = 319;
   public static final int SKY_LIMIT_Y = 320;
   public static final int END_MIN_Y = 1000;
   public static final int END_MAX_Y = 1127;
   public static final int END_Y_OFFSET = 1000;
   public static final int WORLD_MIN_Y = -1008;
   public static final int WORLD_HEIGHT = 2272;
   public static final int WORLD_MAX_Y = 1263;
   public static final int PHANTOM_WARN_Y = 560;
   public static final int PHANTOM_SPAWN_Y = 680;
   public static final int PHANTOM_FULL_Y = 880;
   public static final int ANTI_AIR_ADV_Y = 800;
   public static final int SUNBURN_EXEMPT_Y = 560;
   public static final int NETHER_LIMIT_CHUNKS = 1875000;
   private static volatile ServerLevel overworldLevel;
   private static volatile boolean spawnFixDone;
   private static int configReloadCounter;
   private static final Map<UUID, Integer> LAST_BAND = new ConcurrentHashMap<>();

   public void onInitialize() {
      OneWorldConfig.load();
      Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("merged"), MergedChunkGenerator.CODEC);
      ServerTickEvents.END_SERVER_TICK.register(OneWorld::fixSpawnOnce);
      ServerTickEvents.END_SERVER_TICK.register(DragonLiteHandler::tickServer);
      ServerTickEvents.END_SERVER_TICK.register(SkyDefenseHandler::tickServer);
      ServerTickEvents.END_SERVER_TICK.register(HeatZoneHandler::tickServer);
      ServerTickEvents.END_SERVER_TICK.register(OneWorld::tickPlayerChecks);
      OneWorldAttachments.register();
      ServerLivingEntityEvents.AFTER_DAMAGE.register((AfterDamage)(entity, source, amount, blocked, frozen) -> SkyDefenseHandler.afterDamage(entity, source));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register(
            (AllowDamage)(entity, source, amount) -> entity.level().getDifficulty() != Difficulty.PEACEFUL
               || !(source.getEntity() instanceof Phantom phantom && phantom.entityTags().contains("oneworld_sky"))
         );
      ServerLivingEntityEvents.AFTER_DEATH.register((AfterDeath)(entity, source) -> {
         if (entity.level() instanceof ServerLevel serverLevel && entity instanceof Phantom phantom) {
            SkyDefenseHandler.onEliteDeath(serverLevel, phantom);
         }
      });
      ServerLevelEvents.LOAD.register((Load)(server, level) -> {
         if (level.dimension() == Level.OVERWORLD) {
            overworldLevel = level;
         }
      });
      ServerLifecycleEvents.SERVER_STOPPED.register((ServerStopped)server -> {
         overworldLevel = null;
         spawnFixDone = false;
         LAST_BAND.clear();
         DragonLiteHandler.reset();
         PortalLogic.clearCache();
         SkyDefenseHandler.reset();
      });
      ServerLifecycleEvents.SERVER_STARTING.register((ServerStarting)server -> OneWorldConfig.load());
      ServerTickEvents.END_SERVER_TICK.register(OneWorld::tickConfigReload);
      ServerPlayConnectionEvents.DISCONNECT.register((Disconnect)(handler, server) -> {
         ServerPlayer leaving = handler.getPlayer();
         LAST_BAND.remove(leaving.getUUID());
         if (leaving.level() instanceof ServerLevel serverLevel) {
            SkyDefenseHandler.onPlayerDisconnect(serverLevel, leaving);
         }
      });
      if (System.getProperty("oneworld.autotest") != null || System.getenv("ONEWORLD_AUTOTEST") != null) {
         ServerLifecycleEvents.SERVER_STARTED.register(OneWorld.AutoTest::onServerStarted);
      }

      LOGGER.info("[OneWorld] initialized: nether y {}..{}, overworld middle, end y {}..{}", new Object[]{-1008, -881, 1000, 1127});
   }

   private static void tickConfigReload(MinecraftServer server) {
      if (++configReloadCounter % 20 == 0) {
         OneWorldConfig.maybeReload();
      }
   }

   private static void fixSpawnOnce(MinecraftServer server) {
      if (!spawnFixDone) {
         spawnFixDone = true;
         ServerLevel ow = server.overworld();
         BlockPos spawn = ow.getLevelData().getRespawnData().pos();
         if (spawn.getY() <= -63 || spawn.getY() >= 318) {
            BandHeights.activateOverworld();

            int y;
            try {
               y = ow.getHeight(Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
            } finally {
               BandHeights.deactivate();
            }

            BlockPos fixed = new BlockPos(spawn.getX(), Math.clamp((long)y, -63, 319), spawn.getZ());
            if (ow.getLevelData() instanceof ServerLevelData sld) {
               sld.setSpawn(RespawnData.of(Level.OVERWORLD, fixed, 0.0F, 0.0F));
            }

            LOGGER.info("[OneWorld] moved world spawn {} -> {} (was outside the overworld band)", spawn, fixed);
         }
      }
   }

   public static ServerLevel overworldLevel() {
      return overworldLevel;
   }

   private static void tickPlayerChecks(MinecraftServer server) {
      ServerLevel level = server.overworld();
      if (level != null && level.getGameTime() % 10L == 0L) {
         for (ServerPlayer player : level.players()) {
            SkyDefenseHandler.checkFreefallAdvancement(player);
            int band = player.getY() <= -881.0 ? 1 : (player.getY() >= 1000.0 ? 2 : 0);
            int last = LAST_BAND.getOrDefault(player.getUUID(), 0);
            if (band != last) {
               LAST_BAND.put(player.getUUID(), band);
               if (last == 1 && band != 1) {
                  CriteriaTriggers.CHANGED_DIMENSION.trigger(player, Level.NETHER, Level.OVERWORLD);
               }

               if (last == 2 && band != 2) {
                  CriteriaTriggers.CHANGED_DIMENSION.trigger(player, Level.END, Level.OVERWORLD);
               }

               if (band == 1 && last != 1) {
                  CriteriaTriggers.CHANGED_DIMENSION.trigger(player, Level.OVERWORLD, Level.NETHER);
                  LOGGER.info("[OneWorld] {} entered the nether band (dimension advancement triggers fired)", player.getName().getString());
               }

               if (band == 2 && last != 2) {
                  CriteriaTriggers.CHANGED_DIMENSION.trigger(player, Level.OVERWORLD, Level.END);
                  LOGGER.info("[OneWorld] {} entered the end band (dimension advancement triggers fired)", player.getName().getString());
               }
            }
         }
      }
   }

   public static Identifier id(String path) {
      return Identifier.fromNamespaceAndPath("oneworld", path);
   }

   public static boolean inNetherZone(double y) {
      return y <= -193.0;
   }

   public static boolean inEndZone(double y) {
      return y >= 1000.0;
   }

   public static boolean inHeatZone(double y) {
      return y >= -192.0 && y <= -65.0;
   }

   public static boolean inHotZone(double y) {
      return OneWorldConfig.restrictionsEnabled() ? y <= -65.0 : inNetherZone(y);
   }

   public static void evaporateWater(Level level, BlockPos pos) {
      level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
      if (level instanceof ServerLevel serverLevel) {
         serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 0.75, pos.getZ() + 0.5, 1, 0.1, 0.04, 0.1, 0.0015);
      }
   }

   public static int rawColumnTop(ChunkAccess chunk, int x, int z) {
      Map<Types, Heightmap> maps = ((ChunkAccessHeightmaps)chunk).oneworld$heightmaps();
      Heightmap map = maps.get(Types.WORLD_SURFACE);
      if (map == null) {
         map = maps.get(Types.WORLD_SURFACE_WG);
      }

      return map != null ? map.getFirstAvailable(x & 15, z & 15) : 320;
   }

   private static final class AutoTest {
      static CompletableFuture<Vec3> pendingSpawnSearch;
      static boolean integrityDone = false;
      static final String PT = "[OneWorld] phantom autotest";
      static boolean tickHookRegistered = false;
      static int phantomPhase = -1;
      static boolean phantomPass = true;
      static ServerPlayer skyTestPlayer;
      static Predicate<Phantom> skyOwned;
      static final String FT = "[OneWorld] fluid autotest";
      static boolean fluidPass = true;
      static boolean fluidFinished = false;
      static int fluidTick = -1;
      static Phantom trackedPhantom;
      static double phantomMinY = Double.MAX_VALUE;
      static int phantomWait = 0;
      static OneWorld.AutoTest.IntegrityScan integrityScan;

      static boolean phantomDone() {
         return phantomPhase == -2;
      }

      static boolean fluidDone() {
         return fluidFinished;
      }

      static void onServerStarted(MinecraftServer server) {
         if (!tickHookRegistered) {
            tickHookRegistered = true;
            ServerTickEvents.END_SERVER_TICK
               .register(
                  (EndTick)s -> {
                     int tick = s.getTickCount();
                     if (tick == 200) {
                        try {
                           verify(s.overworld());
                           startIntegrityScan(s.overworld());
                           DragonLiteHandler.autotestStart();
                           phantomPhase = 0;
                           startFluidTest(s.overworld());
                        } catch (Exception var13) {
                           OneWorld.LOGGER.error("[OneWorld] autotest failed", var13);
                           s.halt(false);
                        }
                     } else if (tick > 200) {
                        DragonLiteHandler.autotestTick(s.overworld());

                        try {
                           if (phantomPhase >= 0) {
                              tickPhantomTest(s.overworld());
                           }
                        } catch (Throwable var12) {
                           OneWorld.LOGGER.error("{}: EXCEPTION", "[OneWorld] phantom autotest", var12);
                           phantomPass = false;
                           phantomPhase = -2;
                        }

                        try {
                           tickFluidTest(s.overworld());
                        } catch (Throwable var11) {
                           OneWorld.LOGGER.error("{}: EXCEPTION", "[OneWorld] fluid autotest", var11);
                           fluidPass = false;
                           fluidFinished = true;
                        }

                        if (tick % 4 == 0) {
                           tickIntegrityScan(s.overworld(), 2);
                           if (tick % 20 == 0
                              && pendingSpawnSearch != null
                              && (pendingSpawnSearch.isDone() || tick >= 1200)
                              && (integrityDone || tick >= 6000)
                              && DragonLiteHandler.autotestDone()
                              && phantomDone()
                              && fluidDone()) {
                              try {
                                 OneWorld.LOGGER
                                    .info("[OneWorld] autotest findSpawn result = {}", pendingSpawnSearch.isDone() ? pendingSpawnSearch.join() : "TIMEOUT");
                              } catch (Exception var9) {
                                 OneWorld.LOGGER.error("[OneWorld] autotest findSpawn failed", var9);
                              } finally {
                                 s.halt(false);
                              }
                           }
                        }
                     }
                  }
               );
         }
      }

      private static void ptCheck(boolean ok, String what) {
         if (!ok) {
            phantomPass = false;
            OneWorld.LOGGER.error("{}: FAILED {}", "[OneWorld] phantom autotest", what);
         }
      }

      private static void ftCheck(boolean ok, String what) {
         if (!ok) {
            fluidPass = false;
            OneWorld.LOGGER.error("{}: FAILED {}", "[OneWorld] fluid autotest", what);
         }
      }

      static void startFluidTest(ServerLevel level) {
         int x = 40;
         int z = 40;

         for (int y = -70; y <= -57; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
         }

         level.setBlock(new BlockPos(x, -57, z), Fluids.WATER.defaultFluidState().createLegacyBlock(), 3);
         fluidTick = 0;
         OneWorld.LOGGER.info("{}: started - water source at (40,-57,40), shaft dug down to -70", "[OneWorld] fluid autotest");
      }

      static void tickFluidTest(ServerLevel level) {
         if (fluidTick >= 0) {
            fluidTick++;
            if (fluidTick >= 120) {
               int x = 40;
               int z = 40;
               boolean sourceAlive = level.getFluidState(new BlockPos(x, -57, z)).isSource();
               boolean columnAlive = !level.getFluidState(new BlockPos(x, -64, z)).isEmpty();
               boolean zoneDry = true;
               int lowestWater = Integer.MAX_VALUE;

               for (int y = -70; y <= -65; y++) {
                  if (!level.getFluidState(new BlockPos(x, y, z)).isEmpty()) {
                     zoneDry = false;
                     lowestWater = Math.min(lowestWater, y);
                  }
               }

               OneWorld.LOGGER
                  .info(
                     "{}: sourceAlive={} columnAlive={} zoneDry={}{}",
                     new Object[]{"[OneWorld] fluid autotest", sourceAlive, columnAlive, zoneDry, zoneDry ? "" : " lowestWaterY=" + lowestWater}
                  );
               ftCheck(sourceAlive, "water source above the zone survives");
               ftCheck(columnAlive, "falling water column reaches y=-64");
               ftCheck(zoneDry, "heat zone stays dry (water evaporates at y<=-65)");
               OneWorld.LOGGER.info("{}: {}", "[OneWorld] fluid autotest", fluidPass ? "PASS" : "FAIL");
               level.setBlock(new BlockPos(x, -57, z), Blocks.DEEPSLATE.defaultBlockState(), 3);
               fluidFinished = true;
               fluidTick = -1;
            }
         }
      }

      static void tickPhantomTest(ServerLevel level) {
         switch (phantomPhase) {
            case 0:
               GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("oneworld-skytest".getBytes()), "OneWorldSkyTest");
               skyTestPlayer = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
               skyTestPlayer.setPos(8.5, 700.0, 8.5);
               ServerPlayer player0 = skyTestPlayer;
               skyOwned = p -> p.isAlive() && p.entityTags().contains("oneworld_sky") && p.entityTags().contains("oneworld_sky:" + player0.getUUID());
               SkyDefenseHandler.tickPlayer(level, skyTestPlayer);
               List<? extends Phantom> squad = level.getEntities(EntityTypes.PHANTOM, skyOwned);
               long elites = squad.stream().filter(p -> p.entityTags().contains("oneworld_elite")).count();
               double eliteScale = squad.stream()
                  .filter(p -> p.entityTags().contains("oneworld_elite"))
                  .findFirst()
                  .map(p -> p.getAttributeValue(Attributes.SCALE))
                  .orElse(0.0);
               OneWorld.LOGGER
                  .info("{}: phase0 squad={} elites={} eliteScale={}", new Object[]{"[OneWorld] phantom autotest", squad.size(), elites, eliteScale});
               ptCheck(squad.size() >= 4, "phase0 squad spawns");
               ptCheck(elites == 2L && Math.abs(eliteScale - 2.0) < 0.01, "phase0 two elites with 2x scale");
               phantomPhase = 1;
               break;
            case 1:
               skyTestPlayer.setPos(8.5, 820.0, 8.5);
               SkyDefenseHandler.tickPlayer(level, skyTestPlayer);
               AdvancementHolder holder = level.getServer().getAdvancements().get(OneWorld.id("physical_anti_air_defense"));
               boolean advDone = holder != null && skyTestPlayer.getAdvancements().getOrStartProgress(holder).isDone();
               OneWorld.LOGGER.info("{}: phase1 antiAirAdvancementDone={}", "[OneWorld] phantom autotest", advDone);
               ptCheck(advDone, "phase1 anti-air advancement");
               level.getEntities(EntityTypes.PHANTOM, skyOwned)
                  .stream()
                  .filter(p -> p.entityTags().contains("oneworld_elite"))
                  .findFirst()
                  .ifPresentOrElse(elite -> {
                     SkyDefenseHandler.onEliteDeath(level, elite);
                     elite.discard();
                  }, () -> ptCheck(false, "phase1 elite present"));
               long membranes = level.getEntities(EntityTypes.ITEM, ex -> ex.getItem().is(Items.PHANTOM_MEMBRANE)).size();
               OneWorld.LOGGER.info("{}: phase1 elite membrane drops={}", "[OneWorld] phantom autotest", membranes);
               ptCheck(membranes >= 4L, "phase1 elite membrane drops");
               phantomMinY = Double.MAX_VALUE;
               phantomPhase = 2;
               break;
            case 2:
               trackedPhantom = (Phantom)level.getEntities(EntityTypes.PHANTOM, skyOwned).stream().findFirst().orElse(null);
               ptCheck(trackedPhantom != null, "phase2 phantom to track");
               if (trackedPhantom == null) {
                  phantomPhase = -2;
                  return;
               }

               skyTestPlayer.setPos(8.5, 700.0, 8.5);
               trackedPhantom.setTarget(skyTestPlayer);
               phantomWait = 40;
               phantomPhase = 3;
               break;
            case 3:
               if (--phantomWait <= 0) {
                  if (trackedPhantom != null) {
                     trackedPhantom.setTarget(null);
                  }

                  phantomWait = 220;
                  OneWorld.LOGGER
                     .info(
                        "{}: phase3 target dropped at y={} (phantom y={}); watching for a dive",
                        new Object[]{"[OneWorld] phantom autotest", skyTestPlayer.getY(), trackedPhantom.getY()}
                     );
                  phantomPhase = 4;
               }
               break;
            case 4:
               for (Phantom p : level.getEntities(EntityTypes.PHANTOM, skyOwned)) {
                  phantomMinY = Math.min(phantomMinY, p.getY());
               }

               if (--phantomWait <= 0) {
                  OneWorld.LOGGER.info("{}: phase4 lowestPhantomY={} (must stay >= {})", new Object[]{"[OneWorld] phantom autotest", phantomMinY, 320});
                  ptCheck(phantomMinY >= 320.0, "phase4 phantoms do not dive to the overworld");
                  phantomPhase = 5;
               }
               break;
            case 5:
               skyTestPlayer.setPos(8.5, 1050.0, 8.5);
               SkyDefenseHandler.tickPlayer(level, skyTestPlayer);
               int squadAfter = level.getEntities(EntityTypes.PHANTOM, skyOwned).size();
               OneWorld.LOGGER.info("{}: phase5 squad after reaching End band = {}", "[OneWorld] phantom autotest", squadAfter);
               ptCheck(squadAfter == 0, "phase5 squad dismissed at the End band");

               for (ItemEntity e : level.getEntities(EntityTypes.ITEM, ex -> ex.getItem().is(Items.PHANTOM_MEMBRANE))) {
                  e.discard();
               }

               OneWorld.LOGGER.info("{}: {}", "[OneWorld] phantom autotest", phantomPass ? "PASS" : "FAIL");
               phantomPhase = -2;
               break;
            default:
               phantomPhase = -2;
         }
      }

      private static void verify(ServerLevel level) {
         OneWorld.LOGGER.info("[OneWorld] autotest worldSpawn={}", level.getLevelData().getRespawnData().pos());

         for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
               level.getChunk(cx, cz);
            }
         }

         for (int[] c : new int[][]{{60, 60}, {-60, 60}, {60, -60}, {-60, -60}}) {
            LevelChunk far = level.getChunk(c[0], c[1]);
            int endSolid = 0;
            MutableBlockPos fp = new MutableBlockPos();

            for (int y = 1000; y <= 1127; y++) {
               fp.set(c[0] << 4, y, c[1] << 4);
               if (!far.getBlockState(fp).isAir()) {
                  endSolid++;
               }
            }

            OneWorld.LOGGER
               .info("[OneWorld] autotest far chunk [{},{}] end band solid={} starts={}", new Object[]{c[0], c[1], endSolid, far.getAllStarts().keySet()});
         }

         int ncx = 234439;
         LevelChunk far = level.getChunk(ncx, 0);
         int netherSolid = 0;
         MutableBlockPos np = new MutableBlockPos();

         for (int yx = -1008; yx <= -881; yx++) {
            np.set(ncx << 4, yx, 0);
            if (!far.getBlockState(np).isAir()) {
               netherSolid++;
            }
         }

         OneWorld.LOGGER.info("[OneWorld] autotest far nether chunk [{},0] nether band solid={}", ncx, netherSolid);

         for (Entity e : level.getEntities(EntityTypes.END_CRYSTAL, ex -> true)) {
            BlockPos below = e.blockPosition().below();
            int colX = e.blockPosition().getX();
            int colZ = e.blockPosition().getZ();
            int topSolid = Integer.MIN_VALUE;
            MutableBlockPos cp = new MutableBlockPos();

            for (int yxx = 1127; yxx >= 1000; yxx--) {
               BlockState s = level.getBlockState(cp.set(colX, yxx, colZ));
               if (!s.isAir()) {
                  topSolid = yxx;
                  break;
               }
            }

            OneWorld.LOGGER
               .info(
                  "[OneWorld] autotest crystal at ({}, {}, {}) below={} topSolidY={} topBlock={}",
                  new Object[]{
                     e.getX(),
                     e.getY(),
                     e.getZ(),
                     level.getBlockState(below).getBlock(),
                     topSolid,
                     topSolid == Integer.MIN_VALUE ? "none" : level.getBlockState(cp.setY(topSolid)).getBlock()
                  }
               );
         }

         if (level.getChunkSource().getGenerator() instanceof MergedChunkGenerator gen) {
            MutableBlockPos cp = new MutableBlockPos();

            for (EndSpike spike : gen.endSpikes(level)) {
               int colX = spike.getCenterX();
               int colZ = spike.getCenterZ();
               int topSolid = Integer.MIN_VALUE;

               for (int yxxx = 1127; yxxx >= 1000; yxxx--) {
                  BlockState s = level.getBlockState(cp.set(colX, yxxx, colZ));
                  if (!s.isAir()) {
                     topSolid = yxxx;
                     break;
                  }
               }

               OneWorld.LOGGER
                  .info(
                     "[OneWorld] autotest spike at ({},{}) r={} h={} expectedTopY={} actualTopY={} topBlock={}",
                     new Object[]{
                        colX,
                        colZ,
                        spike.getRadius(),
                        spike.getHeight(),
                        1000 + spike.getHeight() - 1,
                        topSolid,
                        topSolid == Integer.MIN_VALUE ? "none" : level.getBlockState(cp.setY(topSolid)).getBlock()
                     }
                  );
            }
         }

         boolean found = false;
         int foundR = -1;
         Set<String> startsSeen = new HashSet<>();

         for (int r = 64; r <= 136 && !found; r += 8) {
            for (int a = 0; a < 8; a++) {
               int cx = (int)Math.round(Math.cos(a * Math.PI / 4.0) * r);
               int cz = (int)Math.round(Math.sin(a * Math.PI / 4.0) * r);
               ChunkAccess c = level.getChunk(cx, cz);
               c.getAllStarts().forEach((st, start) -> startsSeen.add(st + ":" + (start.isValid() ? "valid" : "x")));
               int solid = 0;
               MutableBlockPos ep = new MutableBlockPos();

               for (int yxxxx = 1000; yxxxx <= 1127; yxxxx++) {
                  ep.set(cx << 4, yxxxx, cz << 4);
                  if (!c.getBlockState(ep).isAir()) {
                     solid++;
                  }
               }

               if (solid > 0 && !found) {
                  found = true;
                  foundR = r;
               }
            }
         }

         OneWorld.LOGGER.info("[OneWorld] autotest outer end island found={} radiusChunks={} starts={}", new Object[]{found, foundR, startsSeen});
         int leakCount = 0;
         int bone = 0;
         StringBuilder leakDetail = new StringBuilder();
         np = new MutableBlockPos();
         int[][] ring = new int[][]{{40, 40}, {-40, 40}, {40, -40}, {-40, -40}, {48, 16}, {-48, -16}, {16, 48}, {-16, -48}};

         for (int[] rc : ring) {
            ChunkAccess c = level.getChunk(rc[0], rc[1]);
            int chunkLeak = 0;

            for (int x = 0; x < 16; x += 8) {
               for (int z = 0; z < 16; z += 8) {
                  for (int yxxxxx = -64; yxxxxx <= 319; yxxxxx++) {
                     np.set((rc[0] << 4) + x, yxxxxx, (rc[1] << 4) + z);
                     BlockState s = c.getBlockState(np);
                     if (s.is(Blocks.NETHER_BRICKS)
                        || s.is(Blocks.BLACKSTONE)
                        || s.is(Blocks.BASALT)
                        || s.is(Blocks.CRIMSON_STEM)
                        || s.is(Blocks.WARPED_STEM)
                        || s.is(Blocks.SOUL_SAND)
                        || s.is(Blocks.SOUL_SOIL)
                        || s.is(Blocks.GLOWSTONE)
                        || s.is(Blocks.NETHERRACK)
                        || s.is(Blocks.END_STONE)
                        || s.is(Blocks.PURPUR_BLOCK)) {
                        leakCount++;
                        chunkLeak++;
                        if (leakDetail.length() < 400) {
                           leakDetail.append(np.toShortString())
                              .append('=')
                              .append(s.getBlock().getName())
                              .append('@')
                              .append(rc[0])
                              .append(',')
                              .append(rc[1])
                              .append(' ');
                        }
                     }

                     if (yxxxxx > 200 && s.is(Blocks.BONE_BLOCK)) {
                        bone++;
                     }
                  }
               }
            }

            if (chunkLeak > 0) {
               StringBuilder starts = new StringBuilder();
               c.getAllStarts().forEach((st, start) -> {
                  if (start != null && start.isValid()) {
                     starts.append(st).append(" start[").append(start.getBoundingBox().minY()).append("..").append(start.getBoundingBox().maxY()).append("]");

                     for (StructurePiece piece : start.getPieces()) {
                        BoundingBox bb = piece.getBoundingBox();
                        starts.append(" p[").append(bb.minY()).append("..").append(bb.maxY()).append("]");
                     }

                     starts.append("; ");
                  }
               });
               int minX = Integer.MAX_VALUE;
               int minY = Integer.MAX_VALUE;
               int minZ = Integer.MAX_VALUE;
               int maxX = Integer.MIN_VALUE;
               int maxY = Integer.MIN_VALUE;
               int maxZ = Integer.MIN_VALUE;
               int count = 0;
               Map<String, Integer> kinds = new TreeMap<>();

               for (int x = 0; x < 16; x++) {
                  for (int z = 0; z < 16; z++) {
                     for (int yxxxxx = -64; yxxxxx <= 319; yxxxxx++) {
                        np.set((rc[0] << 4) + x, yxxxxx, (rc[1] << 4) + z);
                        BlockState sx = c.getBlockState(np);
                        if (sx.is(Blocks.NETHER_BRICKS)
                           || sx.is(Blocks.BLACKSTONE)
                           || sx.is(Blocks.BASALT)
                           || sx.is(Blocks.CRIMSON_STEM)
                           || sx.is(Blocks.WARPED_STEM)
                           || sx.is(Blocks.SOUL_SAND)
                           || sx.is(Blocks.SOUL_SOIL)
                           || sx.is(Blocks.GLOWSTONE)
                           || sx.is(Blocks.NETHERRACK)
                           || sx.is(Blocks.END_STONE)
                           || sx.is(Blocks.PURPUR_BLOCK)) {
                           count++;
                           minX = Math.min(minX, x);
                           minY = Math.min(minY, yxxxxx);
                           minZ = Math.min(minZ, z);
                           maxX = Math.max(maxX, x);
                           maxY = Math.max(maxY, yxxxxx);
                           maxZ = Math.max(maxZ, z);
                           kinds.merge(sx.getBlock().getName().getString(), 1, Integer::sum);
                        }
                     }
                  }
               }

               int netherBlackstone = 0;

               for (int yxxxxxx = -1008; yxxxxxx <= -881 && netherBlackstone < 1000; yxxxxxx++) {
                  np.set((rc[0] << 4) + 8, yxxxxxx, (rc[1] << 4) + 8);
                  BlockState sx = c.getBlockState(np);
                  if (sx.is(Blocks.BLACKSTONE) || sx.is(Blocks.NETHER_BRICKS) || sx.is(Blocks.POLISHED_BLACKSTONE)) {
                     netherBlackstone++;
                  }
               }

               OneWorld.LOGGER
                  .warn(
                     "[OneWorld] autotest leaky chunk ({},{}) leak={} starts: {} | fullLeak={} bbox=local[x {}..{}, y {}..{}, z {}..{}] kinds={} netherBandCol8={}",
                     new Object[]{rc[0], rc[1], chunkLeak, starts, count, minX, maxX, minY, maxY, minZ, maxZ, kinds, netherBlackstone}
                  );
            }
         }

         OneWorld.LOGGER
            .info(
               "[OneWorld] autotest overworld purity: nether/end band leak blocks={} floating bones={} detail={}",
               new Object[]{Integer.valueOf(leakCount), bone, leakDetail}
            );

         for (int[] c : new int[][]{{8, 8}, {24, 8}}) {
            int x = c[0];
            int z = c[1];
            logBand(level, "overworld", x, z, -64, 319);
            logBand(level, "nether", x, z, -1008, -881);
            logBand(level, "gap", x, z, 320, 999);
            logBand(level, "end", x, z, 1000, 1127);
            ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
            MutableBlockPos p = new MutableBlockPos();
            int bedrock = 0;

            for (int yxxxxxxx = -1008; yxxxxxxx <= 1127; yxxxxxxx++) {
               p.set(x, yxxxxxxx, z);
               if (chunk.getBlockState(p).is(Blocks.BEDROCK)) {
                  bedrock++;
               }
            }

            OneWorld.LOGGER.info("[OneWorld] autotest bedrock count at ({},{}) = {}", new Object[]{x, z, bedrock});
            OneWorld.LOGGER
               .info(
                  "[OneWorld] autotest raw heightmaps at ({},{}): MOTION_BLOCKING={} WORLD_SURFACE={} OCEAN_FLOOR={}",
                  new Object[]{
                     x,
                     z,
                     chunk.getHeight(Types.MOTION_BLOCKING, x & 15, z & 15),
                     chunk.getHeight(Types.WORLD_SURFACE, x & 15, z & 15),
                     chunk.getHeight(Types.OCEAN_FLOOR, x & 15, z & 15)
                  }
               );
            ChunkAccess negChunk = level.getChunk(-1, -1);
            BandHeights.activateOverworld();

            try {
               int worldForm = negChunk.getHeight(Types.MOTION_BLOCKING, -3, -9);
               int localForm = negChunk.getHeight(Types.MOTION_BLOCKING, 13, 7);
               OneWorld.LOGGER
                  .info(
                     "[OneWorld] autotest getHeight world-coords vs local-coords: {} vs {} ({})",
                     new Object[]{worldForm, localForm, worldForm == localForm ? "match" : "MISMATCH"}
                  );
            } finally {
               BandHeights.deactivate();
            }
         }

         BlockPos spawn = level.getLevelData().getRespawnData().pos();
         pendingSpawnSearch = PlayerSpawnFinder.findSpawn(level, spawn);
         OneWorld.LOGGER.info("[OneWorld] autotest started findSpawn around {}", spawn);
      }

      private static void logBand(ServerLevel level, String band, int x, int z, int minY, int maxY) {
         MutableBlockPos p = new MutableBlockPos(x, 0, z);
         int solid = 0;
         int air = 0;
         int fluid = 0;
         BlockState top = null;

         for (int y = minY; y <= maxY; y++) {
            p.setY(y);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) {
               air++;
            } else if (!s.getFluidState().isEmpty()) {
               fluid++;
            } else {
               solid++;
               if (top == null) {
                  top = s;
               }
            }
         }

         int topY = level.getHeight(Types.MOTION_BLOCKING, x, z);
         OneWorld.LOGGER
            .info(
               "[OneWorld] autotest band={} at ({},{}) y {}..{}: solid={} air={} fluid={} firstSolid={} heightmapY={}",
               new Object[]{band, x, z, minY, maxY, solid, air, fluid, top, topY}
            );
      }

      static void startIntegrityScan(ServerLevel level) {
         integrityScan = new OneWorld.AutoTest.IntegrityScan(level);
      }

      static void tickIntegrityScan(ServerLevel level, int chunksPerTick) {
         if (integrityScan != null && !integrityScan.done) {
            integrityScan.tick(chunksPerTick);
         } else {
            integrityDone = integrityScan != null && integrityScan.done;
         }
      }

      private static final class IntegrityScan {
         final ArrayDeque<long[]> queue = new ArrayDeque<>();
         final ServerLevel level;
         final Structure bastion;
         final Structure fortress;
         final Predicate<BlockState> netherOnly = s -> s.is(Blocks.BLACKSTONE)
            || s.is(Blocks.POLISHED_BLACKSTONE)
            || s.is(Blocks.POLISHED_BLACKSTONE_BRICKS)
            || s.is(Blocks.GILDED_BLACKSTONE)
            || s.is(Blocks.CHISELED_POLISHED_BLACKSTONE)
            || s.is(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)
            || s.is(Blocks.BASALT)
            || s.is(Blocks.NETHER_BRICKS)
            || s.is(Blocks.NETHER_BRICK_FENCE)
            || s.is(Blocks.CRIMSON_STEM)
            || s.is(Blocks.WARPED_STEM)
            || s.is(Blocks.SOUL_SAND)
            || s.is(Blocks.SOUL_SOIL);
         int scanned = 0;
         int netherBlocksAboveHeat = 0;
         int crossBandPieces = 0;
         int falseStructurePredicates = 0;
         int chunksWithBastionStart = 0;
         int chunksWithFortressStart = 0;
         int islandRadius = -1;
         final StringBuilder detail = new StringBuilder();
         boolean chunksDone = false;
         boolean radiusDone = false;
         boolean done = false;
         private int axis = 0;
         private int r = 170;

         IntegrityScan(ServerLevel level) {
            this.level = level;
            Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            this.bastion = (Structure)structures.getValue(Identifier.withDefaultNamespace("bastion_remnant"));
            this.fortress = (Structure)structures.getValue(Identifier.withDefaultNamespace("fortress"));

            for (int rad = 8; rad <= 64; rad += 11) {
               for (int a = 0; a < 8; a++) {
                  this.queue.add(new long[]{Math.round(Math.cos(a * Math.PI / 4.0) * rad), Math.round(Math.sin(a * Math.PI / 4.0) * rad)});
               }
            }

            this.queue.add(new long[]{60L, -60L});
            this.queue.add(new long[]{-60L, 60L});
         }

         void tick(int budget) {
            if (!this.done) {
               MutableBlockPos probe = new MutableBlockPos();

               while (budget > 0) {
                  if (this.chunksDone) {
                     if (!this.radiusDone) {
                        this.tickIslandRadius(budget, probe);
                        return;
                     }

                     this.finish();
                     return;
                  }

                  long[] rc = this.queue.poll();
                  if (rc == null) {
                     this.chunksDone = true;
                     OneWorld.LOGGER.info("[OneWorld] autotest integrity chunks phase done (scanned={})", this.scanned);
                  } else {
                     this.scanChunk((int)rc[0], (int)rc[1], probe);
                     budget--;
                  }
               }
            }
         }

         private void scanChunk(int cx, int cz, MutableBlockPos probe) {
            this.scanned++;
            ChunkAccess chunk = this.level.getChunk(cx, cz);

            for (int sec = chunk.getSectionIndex(-64); sec <= chunk.getSectionIndex(1127); sec++) {
               LevelChunkSection section = chunk.getSection(sec);
               if (!section.hasOnlyAir() && section.getStates().maybeHas(this.netherOnly)) {
                  int baseY = chunk.getSectionYFromSectionIndex(sec) << 4;

                  for (int x = 0; x < 16 && this.netherBlocksAboveHeat < 32; x++) {
                     for (int z = 0; z < 16 && this.netherBlocksAboveHeat < 32; z++) {
                        for (int y = 0; y < 16 && this.netherBlocksAboveHeat < 32; y++) {
                           BlockState s = section.getBlockState(x, y, z);
                           if (this.netherOnly.test(s)) {
                              this.netherBlocksAboveHeat++;
                              if (this.detail.length() < 400) {
                                 this.detail
                                    .append(s.getBlock().getName())
                                    .append('@')
                                    .append((cx << 4) + x)
                                    .append(',')
                                    .append(baseY + y)
                                    .append(',')
                                    .append((cz << 4) + z)
                                    .append(' ');
                              }
                           }
                        }
                     }
                  }
               }
            }

            for (Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
               StructureStart start = entry.getValue();
               if (start != null && start.isValid()) {
                  String key = entry.getKey().toString();
                  if (key.endsWith("bastion_remnant")) {
                     this.chunksWithBastionStart++;
                  }

                  if (key.endsWith("fortress")) {
                     this.chunksWithFortressStart++;
                  }

                  for (StructurePiece piece : start.getPieces()) {
                     BoundingBox bb = piece.getBoundingBox();
                     boolean inNether = bb.minY() >= -1024 && bb.maxY() <= -881;
                     boolean inOverworld = bb.minY() >= -88 && bb.maxY() <= 319;
                     boolean inEnd = bb.minY() >= 984 && bb.maxY() <= 1143;
                     if (!inNether && !inOverworld && !inEnd) {
                        this.crossBandPieces++;
                        if (this.detail.length() < 700) {
                           this.detail
                              .append(key)
                              .append(" piece y ")
                              .append(bb.minY())
                              .append("..")
                              .append(bb.maxY())
                              .append(" @[")
                              .append(cx)
                              .append(',')
                              .append(cz)
                              .append("] ");
                        }
                     }
                  }
               }
            }

            probe.set((cx << 4) + 8, 100, (cz << 4) + 8);
            if (this.bastion != null && this.level.structureManager().getStructureWithPieceAt(probe.immutable(), this.bastion).isValid()) {
               this.falseStructurePredicates++;
            }

            if (this.fortress != null && this.level.structureManager().getStructureWithPieceAt(probe.immutable(), this.fortress).isValid()) {
               this.falseStructurePredicates++;
            }
         }

         private void tickIslandRadius(int budget, MutableBlockPos probe) {
            while (budget > 0 && this.axis < 8) {
               double dx = Math.cos(this.axis * Math.PI / 4.0);
               double dz = Math.sin(this.axis * Math.PI / 4.0);

               label35:
               while (true) {
                  if (this.r > this.islandRadius && this.r >= 60) {
                     int x = (int)Math.round(dx * this.r);
                     int z = (int)Math.round(dz * this.r);
                     ChunkAccess chunk = this.level.getChunk(x >> 4, z >> 4);
                     probe.set(x, 0, z);
                     boolean solid = false;
                     int y = 1127;

                     while (true) {
                        if (y >= 1000) {
                           if (chunk.getBlockState(probe.setY(y)).isAir()) {
                              y--;
                              continue;
                           }

                           solid = true;
                        }

                        if (solid) {
                           this.islandRadius = Math.max(this.islandRadius, this.r);
                        }

                        this.r -= 2;
                        if (--budget <= 0) {
                           return;
                        }
                        continue label35;
                     }
                  }

                  this.axis++;
                  this.r = 170;
                  break;
               }
            }

            this.radiusDone = true;
         }

         private void finish() {
            this.done = true;
            OneWorld.LOGGER
               .info(
                  "[OneWorld] autotest integrity: scanned={} netherBlocksAboveHeat={} crossBandPieces={} falseAdvancementPredicates={} bastionStartChunks={} fortressStartChunks={} detail={}",
                  new Object[]{
                     this.scanned,
                     this.netherBlocksAboveHeat,
                     this.crossBandPieces,
                     this.falseStructurePredicates,
                     this.chunksWithBastionStart,
                     this.chunksWithFortressStart,
                     this.detail
                  }
               );
            OneWorld.LOGGER.info("[OneWorld] autotest end main island max radius ~= {} blocks (vanilla ~100 incl. slopes)", this.islandRadius);
            MutableBlockPos probe = new MutableBlockPos();
            int endDark = 0;
            int endTries = 0;
            RandomSource random = RandomSource.create(42L);

            for (int[] rc : new int[][]{{0, 0}, {2, 3}, {-4, 2}, {5, -5}}) {
               int x = (rc[0] << 4) + 8;
               int z = (rc[1] << 4) + 8;
               ChunkAccess chunk = this.level.getChunk(rc[0], rc[1]);
               probe.set(x, 0, z);

               for (int y = 1127; y >= 1000; y--) {
                  if (!chunk.getBlockState(probe.setY(y)).isAir()) {
                     probe.setY(y + 1);
                     break;
                  }
               }

               for (int i = 0; i < 40; i++) {
                  endTries++;
                  if (Monster.isDarkEnoughToSpawn(this.level, probe.immutable(), random)) {
                     endDark++;
                  }
               }
            }

            int netherDark = 0;
            int netherTries = 0;

            for (int[] rc : new int[][]{{8, 8}, {24, 8}}) {
               int x = (rc[0] << 4) + 8;
               int z = (rc[1] << 4) + 8;
               ChunkAccess chunk = this.level.getChunk(rc[0], rc[1]);
               probe.set(x, -944, z);

               while (probe.getY() < -881 && chunk.getBlockState(probe).isAir()) {
                  probe.move(Direction.UP);
               }

               for (int ix = 0; ix < 40; ix++) {
                  netherTries++;
                  BlockPos spot = new BlockPos(probe.getX(), probe.getY() + 1, probe.getZ());
                  if (Monster.isDarkEnoughToSpawn(this.level, spot, random)) {
                     netherDark++;
                  }
               }
            }

            OneWorld.LOGGER
               .info(
                  "[OneWorld] autotest monster light rules: endBand dark {}/{} netherBand dark {}/{} (end ~100% minus fire-lit spots, nether ~100% expected)",
                  new Object[]{endDark, endTries, netherDark, netherTries}
               );
            int inNetherBand = 0;
            int inOverworldBand = 0;
            int inEndBand = 0;

            for (Entity entity : this.level.getAllEntities()) {
               double yx = entity.getY();
               if (yx <= -881.0) {
                  inNetherBand++;
               } else if (yx <= 319.0) {
                  inOverworldBand++;
               } else if (yx >= 1000.0) {
                  inEndBand++;
               }
            }

            OneWorld.LOGGER.info("[OneWorld] autotest entity census: nether={} overworld={} end={}", new Object[]{inNetherBand, inOverworldBand, inEndBand});
         }
      }
   }
}
