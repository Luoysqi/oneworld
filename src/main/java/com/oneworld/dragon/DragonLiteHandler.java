package com.oneworld.dragon;

import com.oneworld.OneWorld;
import com.oneworld.worldgen.MergedChunkGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Plane;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature.EndSpike;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class DragonLiteHandler {
   private static final int DRAGON_SPAWN_Y = 1120;
   private static final int POLL_INTERVAL = 20;
   private static final ChunkPos ARENA = new ChunkPos(0, 0);
   private static int tickCounter = 0;
   private static boolean sawDragon = false;
   private static boolean deathHandled = false;
   private static BlockPos podiumPos = null;
   private static DragonLiteHandler.RespawnStage stage = null;
   private static int respawnTime = 0;
   private static List<EndCrystal> respawnCrystals = List.of();
   private static int partialLogCooldown = 0;
   private static List<Integer> gatewayRing = null;
   private static Vec3 lastDragonPos = null;
   private static ServerBossEvent dragonBossEvent;
   private static final String AT = "[OneWorld] dragon autotest";
   private static int atPhase = -1;
   private static int atWait = 0;
   private static double atMinDragonY = Double.MAX_VALUE;
   private static boolean atPass = true;
   private static String atFail = "";

   private DragonLiteHandler() {
   }

   public static void reset() {
      tickCounter = 0;
      sawDragon = false;
      deathHandled = false;
      podiumPos = null;
      stage = null;
      respawnTime = 0;
      respawnCrystals = List.of();
      partialLogCooldown = 0;
      gatewayRing = null;
      lastDragonPos = null;
      if (dragonBossEvent != null) {
         dragonBossEvent.removeAllPlayers();
         dragonBossEvent = null;
      }
   }

   // 原版 getHeightmapPos 只使用传入坐标的 x/z，返回该柱的高度图顶部；
   // 龙的降落/栖息/起飞/死亡各阶段传入的都是基座坐标（y≈1061），
   // 原版返回的是喷泉柱顶（≈1066）。合并维度的高度图被钳制在主世界带，
   // 因此这里一律扫描末地带（1000..1127）取柱顶，且不能按传入 y 分流——
   // 否则栖息目标会变成基座底部（y=1061），龙降落时整只穿进基岩喷泉。
   public static BlockPos bandDragonSurface(Level level, Types type, BlockPos vanillaPos) {
      if (level != null && level.dimension() == Level.OVERWORLD && !level.isClientSide()) {
         int x = vanillaPos.getX();
         int z = vanillaPos.getZ();
         LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
         Predicate<BlockState> opaque = type.isOpaque();
         MutableBlockPos p = new MutableBlockPos(x, 0, z);

         for (int y = 1127; y >= 1000; y--) {
            p.setY(y);
            if (opaque.test(chunk.getBlockState(p))) {
               return vanillaPos.atY(y + 1);
            }
         }

         return vanillaPos.atY(1000);
      } else {
         return vanillaPos;
      }
   }

   public static void tickServer(MinecraftServer server) {
      ServerLevel overworld = server.overworld();
      if (overworld != null) {
         tickCounter++;
         boolean playerInEnd = anyPlayerInEnd(overworld);
         if (playerInEnd) {
            overworld.getChunkSource().addTicketWithRadius(TicketType.DRAGON, ARENA, 9);
         } else {
            overworld.getChunkSource().removeTicketWithRadius(TicketType.DRAGON, ARENA, 9);
         }

         updateBossEvent(overworld);
         if (stage != null) {
            if (playerInEnd) {
               tickRespawn(overworld);
            }
         } else if (tickCounter % 20 == 0 && playerInEnd) {
            step(overworld);
         }
      }
   }

   // 原版的龙血条由 EnderDragonFight 管理（updateDragon/updatePlayers）；
   // 合并世界的主世界维度没有 fight 对象（EnderDragon 上的 dragonFight 恒为 null），
   // 因此由本模组按原版规则逐条复刻：粉色进度条、Boss 音乐、黑屏雾效、
   // 玩家筛选（存活且距基座中心 192 格内，每 20 tick 扫描）、死亡动画期间保持 0 进度。
   private static void updateBossEvent(ServerLevel level) {
      EnderDragon dragon = findDragon(level);
      if (dragon != null) {
         lastDragonPos = dragon.position();
         if (dragonBossEvent == null) {
            dragonBossEvent = new ServerBossEvent(
               Mth.createInsecureUUID(level.getRandom()),
               Component.translatable("entity.minecraft.ender_dragon"),
               BossEvent.BossBarColor.PINK,
               BossEvent.BossBarOverlay.PROGRESS
            );
            dragonBossEvent.setPlayBossMusic(true);
            // 注意：不能开启 setCreateWorldFog —— 原版渲染器在 boss 雾效激活时会把天空替换为纯雾色。
            // 原版末地雾色与天空色相近看不出来；但合并世界是主世界天空盒，末地主岛（龙所在处）会整片变黑。
            // 因此只保留血条与 Boss 音乐，天空交给模组自己的末地渐变渲染（与 1.6.4 一致）。
         }

         dragonBossEvent.setProgress(Mth.clamp(dragon.getHealth() / dragon.getMaxHealth(), 0.0F, 1.0F));
         if (dragon.hasCustomName()) {
            dragonBossEvent.setName(dragon.getDisplayName());
         }

         if (tickCounter % 20 == 0) {
            BlockPos origin = podiumOrigin(level);
            double ox = origin.getX();
            double oy = origin.getY() + 128.0;
            double oz = origin.getZ();
            Set<ServerPlayer> tracking = new HashSet<>();

            for (ServerPlayer player : level.players()) {
               if (player.isAlive() && player.distanceToSqr(ox, oy, oz) < 192.0 * 192.0) {
                  dragonBossEvent.addPlayer(player);
                  tracking.add(player);
               }
            }

            for (ServerPlayer player : new HashSet<>(dragonBossEvent.getPlayers())) {
               if (!tracking.contains(player)) {
                  dragonBossEvent.removePlayer(player);
               }
            }
         }
      } else if (dragonBossEvent != null) {
         dragonBossEvent.removeAllPlayers();
         dragonBossEvent = null;
      }
   }

   private static EnderDragon findDragon(ServerLevel level) {
      return level.getEntities(EntityTypes.ENDER_DRAGON, d -> d.isAlive() && d.getY() >= 1000.0).stream().findFirst().orElse(null);
   }

   private static boolean anyPlayerInEnd(ServerLevel level) {
      for (ServerPlayer player : level.players()) {
         if (player.getY() >= 1000.0) {
            return true;
         }
      }

      return false;
   }

   static void step(ServerLevel level) {
      if (stage == null) {
         if (partialLogCooldown > 0) {
            partialLogCooldown--;
         }

         boolean alive = !noDragonAlive(level);
         if (alive) {
            sawDragon = true;
         }

         boolean finished = !alive && isFightFinished(level);
         if (!finished) {
            if (alive) {
               return;
            }

            if (!sawDragon) {
               BlockPos origin = podiumOrigin(level);
               List<EndCrystal> crystals = findRespawnCrystals(level, origin);
               if (crystals != null) {
                  startRespawn(level, origin, crystals);
               } else if (!eggExists(level, origin) && !anyGatewayOnRing(level)) {
                  placePodium(level, origin, false);
                  summonDragon(level);
               } else {
                  sawDragon = true;
                  deathHandled = true;
                  placePodium(level, origin, true);
               }
            } else if (!deathHandled) {
               onDragonDefeated(level);
            }
         } else if (sawDragon && !deathHandled) {
            onDragonDefeated(level);
         } else if (!deathHandled && !eggExists(level, podiumOrigin(level)) && !anyGatewayOnRing(level)) {
            onDragonDefeated(level);
         } else {
            List<EndCrystal> crystals = findRespawnCrystals(level, podiumOrigin(level));
            if (crystals != null) {
               startRespawn(level, podiumOrigin(level), crystals);
            }
         }
      }
   }

   private static boolean noDragonAlive(ServerLevel level) {
      return level.getEntities(EntityTypes.ENDER_DRAGON, d -> d.isAlive() && d.getY() >= 1000.0).isEmpty();
   }

   private static boolean isFightFinished(ServerLevel level) {
      MutableBlockPos p = new MutableBlockPos();

      for (int y = 1000; y <= 1140; y++) {
         for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
               p.set(dx, y, dz);
               if (level.getBlockState(p).is(Blocks.END_PORTAL)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private static void summonDragon(ServerLevel level) {
      sawDragon = true;
      int crystals = level.getEntities(EntityTypes.END_CRYSTAL, c -> c.isAlive() && c.getY() >= 1000.0 && c.getY() <= 1127.0).size();
      OneWorld.LOGGER.info("[OneWorld] End dragon fight started: {} worldgen crystals on the spikes", crystals);
      EnderDragon dragon = (EnderDragon)EntityTypes.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT);
      if (dragon != null) {
         dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
         dragon.snapTo(0.5, 1120.0, 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
         dragon.setFightOrigin(new BlockPos(0, podiumOrigin(level).getY(), 0));
         level.addFreshEntity(dragon);
      }
   }

   private static void onDragonDefeated(ServerLevel level) {
      deathHandled = true;
      BlockPos origin = podiumOrigin(level);
      // 原版语义（EnderDragonFight.setDragonKilled）：hasPreviouslyKilledDragon 是持久化标志，
      // 只有从未击杀过时才放龙蛋。合并世界没有 fight 存档，改用世界状态推导：
      // 蛋还在（含被打飞后的原位判定）或折跃门环上已有门，都说明击杀过。
      // 生存模式下折跃门无法破坏，因此"门环为空 + 无蛋"只可能是从未击杀，与原版一致。
      boolean firstKill = !eggExists(level, origin) && !anyGatewayOnRing(level);
      placePodium(level, origin, true);
      if (firstKill) {
         level.setBlockAndUpdate(origin.above(4), Blocks.DRAGON_EGG.defaultBlockState());
      }

      placeGateway(level);
      if (firstKill) {
         // 原版经验精确值（EnderDragon.tickDeath）：死亡动画期间 10 批 floor(xp*0.08F) + 末批 floor(xp*0.2F)。
         // 首杀 12000 -> 10*959+2400 = 11990；复杀 500 -> 10*39+100 = 490。
         // 合并世界龙没有 fight 对象，原版动画路径固定按 500 发放（490），
         // 因此这里补发差额 11500，首杀合计恰好 11990，与原版分毫不差；复杀不再补发（490，同原版）。
         // 发放位置用龙最后的位置（与原版经验球在龙身上发放一致）；
         // 不能在基座中心发：经验球会直接落在返回传送门上被传回主世界出生点，导致玩家捡不到。
         if (level.getGameRules().get(GameRules.MOB_DROPS)) {
            Vec3 xpPos = lastDragonPos != null ? lastDragonPos : Vec3.atCenterOf(origin.above(6));
            ExperienceOrb.award(level, xpPos, 11500);
         }
      }

      OneWorld.LOGGER.info("[OneWorld] End dragon defeated: exit portal active at {} (firstKill={})", origin, firstKill);
   }

   private static boolean eggExists(ServerLevel level, BlockPos origin) {
      MutableBlockPos p = new MutableBlockPos();

      for (int dx = -5; dx <= 5; dx++) {
         for (int dz = -5; dz <= 5; dz++) {
            for (int dy = 0; dy <= 8; dy++) {
               p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
               if (level.getBlockState(p).is(Blocks.DRAGON_EGG)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private static void placeGateway(ServerLevel level) {
      int gateway = nextGatewayIndex(level);
      if (gateway >= 0) {
         int x = Mth.floor(96.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 20) * gateway)));
         int z = Mth.floor(96.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 20) * gateway)));
         BlockPos pos = new BlockPos(x, 1075, z);
         level.levelEvent(3000, pos, 0);
         EndBandGateways.placeDelayedGateway(level, pos);
         OneWorld.LOGGER.info("[OneWorld] vanilla end gateway {} placed at ring slot {} {}", new Object[]{level.getBlockState(pos).getBlock(), gateway, pos});
      }
   }

   private static synchronized int nextGatewayIndex(ServerLevel level) {
      if (gatewayRing == null) {
         gatewayRing = new ArrayList<>();

         for (int i = 0; i < 20; i++) {
            gatewayRing.add(i);
         }

         RandomSource shuffle = RandomSource.create(level.getSeed());
         Collections.shuffle(gatewayRing, new Random(shuffle.nextLong()));
         gatewayRing.removeIf(idx -> {
            int x = Mth.floor(96.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 20) * idx.intValue())));
            int z = Mth.floor(96.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 20) * idx.intValue())));
            BlockPos pos = new BlockPos(x, 1075, z);
            return level.getChunk(x >> 4, z >> 4).getBlockState(pos).is(Blocks.END_GATEWAY);
         });
      }

      return gatewayRing.isEmpty() ? -1 : gatewayRing.remove(gatewayRing.size() - 1);
   }

   private static boolean anyGatewayOnRing(ServerLevel level) {
      for (int i = 0; i < 20; i++) {
         int x = Mth.floor(96.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 20) * i)));
         int z = Mth.floor(96.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 20) * i)));
         BlockPos pos = new BlockPos(x, 1075, z);
         if (level.getChunk(x >> 4, z >> 4).getBlockState(pos).is(Blocks.END_GATEWAY)) {
            return true;
         }
      }

      return false;
   }

   private static List<EndCrystal> findRespawnCrystals(ServerLevel level, BlockPos origin) {
      List<EndCrystal> result = new ArrayList<>();
      int missingSides = 0;
      StringBuilder sides = new StringBuilder();

      for (Direction dir : Plane.HORIZONTAL) {
         BlockPos near = origin.relative(dir, 2);
         BlockPos far = origin.relative(dir, 4);
         AABB box = new AABB(
            Math.min(near.getX(), far.getX()) - 1,
            origin.getY() - 1,
            Math.min(near.getZ(), far.getZ()) - 1,
            Math.max(near.getX(), far.getX()) + 2,
            origin.getY() + 2,
            Math.max(near.getZ(), far.getZ()) + 2
         );
         List<EndCrystal> found = level.getEntitiesOfClass(EndCrystal.class, box);
         if (found.isEmpty()) {
            missingSides++;
            sides.append(dir.getName());
         } else {
            result.addAll(found);
         }
      }

      if (missingSides > 0) {
         if (missingSides < 4 && partialLogCooldown <= 0) {
            OneWorld.LOGGER.info("[OneWorld] End respawn: crystals on {}/4 podium sides (missing: {})", 4 - missingSides, sides);
            partialLogCooldown = 200;
         }

         return null;
      } else {
         return result;
      }
   }

   private static void startRespawn(ServerLevel level, BlockPos origin, List<EndCrystal> crystals) {
      MutableBlockPos p = new MutableBlockPos();

      for (int dx = -4; dx <= 4; dx++) {
         for (int dz = -4; dz <= 4; dz++) {
            for (int dy = -1; dy <= 5; dy++) {
               p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
               BlockState s = level.getBlockState(p);
               if (s.is(Blocks.BEDROCK) || s.is(Blocks.END_PORTAL)) {
                  level.setBlock(p, Blocks.END_STONE.defaultBlockState(), 3);
               }
            }
         }
      }

      placePodium(level, origin, false);
      respawnCrystals = crystals;
      stage = DragonLiteHandler.RespawnStage.START;
      respawnTime = 0;
      sawDragon = false;
      deathHandled = false;
      OneWorld.LOGGER.info("[OneWorld] End dragon respawn triggered by 4 crystals on the podium at {}", origin);
   }

   private static void tickRespawn(ServerLevel level) {
      boolean anyRemoved = false;

      for (EndCrystal crystal : respawnCrystals) {
         if (crystal.isRemoved()) {
            anyRemoved = true;
            break;
         }
      }

      if (anyRemoved) {
         abortRespawn(level);
      } else {
         BlockPos origin = podiumOrigin(level);
         BlockPos beamPos = new BlockPos(0, 1120, 0);
         switch (stage) {
            case START:
               for (EndCrystal crystalx : respawnCrystals) {
                  crystalx.setBeamTarget(beamPos);
               }

               stage = DragonLiteHandler.RespawnStage.PREPARING_TO_SUMMON_PILLARS;
               respawnTime = 0;
               break;
            case PREPARING_TO_SUMMON_PILLARS:
               if (respawnTime < 100) {
                  if (respawnTime == 0 || respawnTime == 50 || respawnTime == 51 || respawnTime == 52 || respawnTime >= 95) {
                     level.levelEvent(3001, beamPos, 0);
                  }

                  respawnTime++;
               } else {
                  stage = DragonLiteHandler.RespawnStage.SUMMONING_PILLARS;
                  respawnTime = 0;
               }
               break;
            case SUMMONING_PILLARS:
               boolean startOfBeam = respawnTime % 40 == 0;
               boolean endOfBeam = respawnTime % 40 == 39;
               if (startOfBeam || endOfBeam) {
                  int index = respawnTime / 40;
                  List<EndSpike> spikes = spikes(level);
                  if (index < spikes.size()) {
                     EndSpike spike = spikes.get(index);
                     if (startOfBeam) {
                        for (EndCrystal crystalx : respawnCrystals) {
                           crystalx.setBeamTarget(new BlockPos(spike.getCenterX(), 1000 + spike.getHeight() + 1, spike.getCenterZ()));
                        }
                     } else {
                        rebuildSpike(level, spike);
                     }
                  } else if (startOfBeam) {
                     stage = DragonLiteHandler.RespawnStage.SUMMONING_DRAGON;
                     respawnTime = 0;
                  }
               }

               respawnTime++;
               break;
            case SUMMONING_DRAGON:
               if (respawnTime >= 100) {
                  resetSpikeCrystals(level);

                  for (EndCrystal crystalx : respawnCrystals) {
                     crystalx.setBeamTarget(null);
                     level.explode(crystalx, crystalx.getX(), crystalx.getY(), crystalx.getZ(), 6.0F, ExplosionInteraction.NONE);
                     crystalx.discard();
                  }

                  stage = null;
                  respawnTime = 0;
                  respawnCrystals = List.of();
                  EnderDragon dragon = (EnderDragon)EntityTypes.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT);
                  if (dragon != null) {
                     dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
                     dragon.snapTo(0.5, 1120.0, 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
                     dragon.setFightOrigin(new BlockPos(0, origin.getY(), 0));
                     level.addFreshEntity(dragon);

                     for (ServerPlayer player : level.players()) {
                        if (player.getY() >= 1000.0) {
                           CriteriaTriggers.SUMMONED_ENTITY.trigger(player, dragon);
                        }
                     }
                  }

                  sawDragon = true;
                  deathHandled = false;
                  OneWorld.LOGGER.info("[OneWorld] End dragon respawned after the vanilla pillar animation");
               } else if (respawnTime >= 80) {
                  level.levelEvent(3001, beamPos, 0);
                  respawnTime++;
               } else {
                  if (respawnTime == 0) {
                     for (EndCrystal crystalx : respawnCrystals) {
                        crystalx.setBeamTarget(beamPos);
                     }
                  } else if (respawnTime < 5) {
                     level.levelEvent(3001, beamPos, 0);
                  }

                  respawnTime++;
               }
         }
      }
   }

   private static void abortRespawn(ServerLevel level) {
      OneWorld.LOGGER.info("[OneWorld] End dragon respawn aborted (a podium crystal was destroyed)");
      stage = null;
      respawnTime = 0;
      respawnCrystals = List.of();
      resetSpikeCrystals(level);
      placePodium(level, podiumOrigin(level), true);
   }

   private static void resetSpikeCrystals(ServerLevel level) {
      for (EndSpike spike : spikes(level)) {
         int cx = spike.getCenterX();
         int cz = spike.getCenterZ();
         int r = Math.max(spike.getRadius(), 2);
         AABB box = new AABB(cx - r, 1000.0, cz - r, cx + r + 1, 1128.0, cz + r + 1);

         for (EndCrystal crystal : level.getEntitiesOfClass(EndCrystal.class, box)) {
            crystal.setInvulnerable(false);
            crystal.setBeamTarget(null);
         }
      }
   }

   private static void rebuildSpike(ServerLevel level, EndSpike spike) {
      int cx = spike.getCenterX();
      int cz = spike.getCenterZ();
      int r = spike.getRadius();
      int height = 1000 + spike.getHeight();
      level.getChunk(cx >> 4, cz >> 4);
      MutableBlockPos p = new MutableBlockPos();

      for (int dx = -10; dx <= 10; dx++) {
         for (int dz = -10; dz <= 10; dz++) {
            for (int dy = -10; dy <= 10; dy++) {
               p.set(cx + dx, height + dy, cz + dz);
               if (p.getY() >= 1000 && p.getY() <= 1127) {
                  level.removeBlock(p, false);
               }
            }
         }
      }

      level.explode(null, cx + 0.5F, height, cz + 0.5F, 5.0F, ExplosionInteraction.BLOCK);
      BlockPos beamTarget = new BlockPos(0, 1120, 0);
      int surface = 1065;

      for (int dx = -r; dx <= r; dx++) {
         for (int dz = -r; dz <= r; dz++) {
            boolean inColumn = dx * dx + dz * dz <= r * r + 1;

            for (int y = 1000; y <= height + 10 && y <= 1127; y++) {
               p.set(cx + dx, y, cz + dz);
               if (y < height) {
                  if (inColumn) {
                     level.setBlock(p, Blocks.OBSIDIAN.defaultBlockState(), 3);
                  }
               } else if (y > surface) {
                  level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
               }
            }
         }
      }

      if (spike.isGuarded()) {
         for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
               boolean xEdge = Math.abs(dx) == 2;
               boolean zEdge = Math.abs(dz) == 2;

               for (int dyx = 0; dyx <= 3; dyx++) {
                  boolean topLayer = dyx == 3;
                  if (xEdge || zEdge || topLayer) {
                     boolean xSide = xEdge || topLayer;
                     boolean zSide = zEdge || topLayer;
                     BlockState bars = (BlockState)((BlockState)((BlockState)((BlockState)Blocks.IRON_BARS
                                 .defaultBlockState()
                                 .setValue(IronBarsBlock.NORTH, xSide && dz != -2))
                              .setValue(IronBarsBlock.SOUTH, xSide && dz != 2))
                           .setValue(IronBarsBlock.WEST, zSide && dx != -2))
                        .setValue(IronBarsBlock.EAST, zSide && dx != 2);
                     level.setBlock(p.set(cx + dx, height + dyx, cz + dz), bars, 3);
                  }
               }
            }
         }
      }

      EndCrystal crystal = (EndCrystal)EntityTypes.END_CRYSTAL.create(level, EntitySpawnReason.STRUCTURE);
      if (crystal != null) {
         crystal.setBeamTarget(beamTarget);
         crystal.setInvulnerable(true);
         crystal.snapTo(cx + 0.5, height + 1, cz + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
         level.addFreshEntity(crystal);
         BlockPos crystalPos = crystal.blockPosition();
         level.setBlockAndUpdate(crystalPos.below(), Blocks.BEDROCK.defaultBlockState());
         level.setBlockAndUpdate(crystalPos, FireBlock.getState(level, crystalPos));
      }
   }

   private static List<EndSpike> spikes(ServerLevel level) {
      return level.getChunkSource().getGenerator() instanceof MergedChunkGenerator gen ? gen.endSpikes(level) : EndSpikeFeature.getSpikesForLevel(level);
   }

   private static BlockPos podiumOrigin(ServerLevel level) {
      if (podiumPos == null) {
         podiumPos = findPodiumOrigin(level);
      }

      return podiumPos;
   }

   private static BlockPos findPodiumOrigin(ServerLevel level) {
      level.getChunk(0, 0);
      int[][] probes = new int[][]{{2, 0}, {0, 2}, {-2, 0}, {0, -2}, {1, 1}, {-1, -1}, {2, 1}};

      for (int[] probe : probes) {
         for (int y = 1127; y >= 1000; y--) {
            if (level.getBlockState(new BlockPos(probe[0], y, probe[1])).is(Blocks.END_PORTAL)) {
               return new BlockPos(0, y, 0);
            }
         }
      }

      for (int yx = 1127; yx >= 1000; yx--) {
         if (level.getBlockState(new BlockPos(3, yx, 0)).is(Blocks.BEDROCK) && level.getBlockState(new BlockPos(2, yx, 0)).isAir()) {
            return new BlockPos(0, yx, 0);
         }
      }

      int top = Integer.MIN_VALUE;

      for (int yxx = 1127; yxx >= 1000; yxx--) {
         BlockState s = level.getBlockState(new BlockPos(0, yxx, 0));
         if (!s.is(Blocks.DRAGON_EGG) && !s.is(Blocks.BEDROCK) && !s.isAir() && s.blocksMotion()) {
            top = yxx;
            break;
         }
      }

      int originY = top == Integer.MIN_VALUE ? 1075 : Math.min(top, 1091);
      return new BlockPos(0, originY, 0);
   }

   private static void placePodium(ServerLevel level, BlockPos origin, boolean active) {
      new EndPodiumFeature(active).place(FeatureConfiguration.NONE, level, level.getChunkSource().getGenerator(), RandomSource.create(), origin);
   }

   public static void autotestStart() {
      atPhase = 0;
      atWait = 0;
      atMinDragonY = Double.MAX_VALUE;
      atPass = true;
      atFail = "";
      OneWorld.LOGGER.info("{}: start", "[OneWorld] dragon autotest");
   }

   public static boolean autotestDone() {
      return atPhase < 0;
   }

   private static void atCheck(boolean ok, String what) {
      if (!ok) {
         atPass = false;
         atFail = atFail.isEmpty() ? what : atFail + "; " + what;
         OneWorld.LOGGER.error("{}: FAILED {}", "[OneWorld] dragon autotest", what);
      }
   }

   public static void autotestTick(ServerLevel level) {
      if (atPhase >= 0) {
         level.getChunkSource().addTicketWithRadius(TicketType.DRAGON, ARENA, 9);

         try {
            switch (atPhase) {
               case 0:
                  for (EnderDragon dragon : level.getEntities(EntityTypes.ENDER_DRAGON, d -> true)) {
                     dragon.discard();
                  }

                  BlockPos old = findPodiumOrigin(level);

                  for (EndCrystal stray : level.getEntitiesOfClass(EndCrystal.class, new AABB(-6.0, old.getY() - 2, -6.0, 7.0, old.getY() + 7, 7.0))) {
                     stray.discard();
                  }

                  MutableBlockPos wipe = new MutableBlockPos();

                  for (int dx = -5; dx <= 5; dx++) {
                     for (int dz = -5; dz <= 5; dz++) {
                        for (int dyx = -2; dyx <= 6; dyx++) {
                           wipe.set(dx, old.getY() + dyx, dz);
                           BlockState s = level.getBlockState(wipe);
                           if (s.is(Blocks.END_PORTAL) || s.is(Blocks.BEDROCK) || s.is(Blocks.DRAGON_EGG) || s.is(Blocks.WALL_TORCH)) {
                              level.setBlock(wipe, dyx >= 1 ? Blocks.AIR.defaultBlockState() : Blocks.END_STONE.defaultBlockState(), 3);
                           }
                        }
                     }
                  }

                  reset();
                  gatewayRing = null;
                  step(level);
                  BlockPos o = podiumPos;
                  boolean dragon = !noDragonAlive(level);
                  boolean ring = o != null && level.getBlockState(new BlockPos(3, o.getY(), 0)).is(Blocks.BEDROCK);
                  boolean noPortal = !isFightFinished(level);
                  OneWorld.LOGGER
                     .info(
                        "{}: phase0 firstVisit dragon={} origin={} ringBedrock={} portalInactive={}",
                        new Object[]{"[OneWorld] dragon autotest", dragon, o, ring, noPortal}
                     );
                  atCheck(dragon, "phase0 dragon summoned");
                  atCheck(ring, "phase0 vanilla ring bedrock");
                  atCheck(noPortal, "phase0 portal inactive");
                  atPhase = 1;
                  break;
               case 1:
                  for (EnderDragon dragon1 : level.getEntities(EntityTypes.ENDER_DRAGON, d -> true)) {
                     dragon1.discard();
                  }

                  step(level);
                  BlockPos o1 = podiumPos;
                  atCheck(isFightFinished(level), "phase1 portal active after kill");
                  atCheck(o1 != null && level.getBlockState(o1.above(4)).is(Blocks.DRAGON_EGG), "phase1 egg on pillar");
                  reset();
                  step(level);
                  atCheck(noDragonAlive(level), "phase1 no auto respawn after relog");
                  atPhase = 2;
                  break;
               case 2:
                  BlockPos o2 = podiumPos;

                  for (Direction dir : Plane.HORIZONTAL) {
                     BlockPos rim = o2.relative(dir, 3);
                     EndCrystal crystal = new EndCrystal(level, rim.getX() + 0.5, rim.getY() + 1, rim.getZ() + 0.5);
                     crystal.setShowBottom(false);
                     level.addFreshEntity(crystal);
                  }

                  step(level);
                  OneWorld.LOGGER.info("{}: phase2 respawn stage={}", "[OneWorld] dragon autotest", stage);
                  atCheck(stage != null, "phase2 respawn started by 4 crystals");
                  atPhase = 3;
                  break;
               case 3:
                  for (int i = 0; i < 40 && stage != null; i++) {
                     tickRespawn(level);
                  }

                  if (stage == null) {
                     long dragons = level.getEntities(EntityTypes.ENDER_DRAGON, d -> true).size();
                     boolean portalOff = !isFightFinished(level);
                     long podiumCrystals = level.getEntitiesOfClass(
                           EndCrystal.class, new AABB(-6.0, podiumPos.getY() - 2, -6.0, 7.0, podiumPos.getY() + 7, 7.0)
                        )
                        .size();
                     int spikeCrystals = level.getEntities(EntityTypes.END_CRYSTAL, c -> c.getY() >= 1000.0 && c.getY() <= 1127.0).size();
                     OneWorld.LOGGER
                        .info(
                           "{}: phase3 dragons={} portalOff={} podiumCrystals={} spikeCrystals={}",
                           new Object[]{"[OneWorld] dragon autotest", dragons, portalOff, podiumCrystals, spikeCrystals}
                        );
                     atCheck(dragons == 1L, "phase3 exactly one dragon after animation");
                     atCheck(portalOff, "phase3 portal deactivated");
                     atCheck(podiumCrystals == 0L, "phase3 podium crystals consumed");
                     atCheck(spikeCrystals >= 10, "phase3 spikes rebuilt with crystals");
                     atPhase = 4;
                  }
                  break;
               case 4:
                  if (atWait < 300) {
                     atWait++;

                     for (EnderDragon dragon4 : level.getEntities(EntityTypes.ENDER_DRAGON, LivingEntity::isAlive)) {
                        atMinDragonY = Math.min(atMinDragonY, dragon4.getY());
                     }

                     return;
                  }

                  OneWorld.LOGGER
                     .info("{}: phase4 flight check lowestDragonY={} (must stay >= {})", new Object[]{"[OneWorld] dragon autotest", atMinDragonY, 1000});
                  atCheck(atMinDragonY >= 1000.0, "phase4 dragon stays in the End band");

                  for (EnderDragon dragon4b : level.getEntities(EntityTypes.ENDER_DRAGON, d -> true)) {
                     dragon4b.discard();
                  }

                  step(level);
                  atCheck(isFightFinished(level), "phase4 portal re-active after second kill");
                  int eggs = 0;
                  MutableBlockPos p = new MutableBlockPos();

                  for (int dx = -5; dx <= 5; dx++) {
                     for (int dz = -5; dz <= 5; dz++) {
                        for (int dy = 0; dy <= 8; dy++) {
                           p.set(dx, podiumPos.getY() + dy, dz);
                           if (level.getBlockState(p).is(Blocks.DRAGON_EGG)) {
                              eggs++;
                           }
                        }
                     }
                  }

                  atCheck(eggs == 1, "phase4 no duplicate egg");
                  atPhase = -1;
                  OneWorld.LOGGER.info("{}: {} {}", new Object[]{"[OneWorld] dragon autotest", atPass ? "PASS" : "FAIL", atFail});
                  break;
               default:
                  atPhase = -1;
            }
         } catch (Throwable var7) {
            OneWorld.LOGGER.error("{}: EXCEPTION", "[OneWorld] dragon autotest", var7);
            atPass = false;
            atFail = String.valueOf(var7);
            atPhase = -1;
         }
      }
   }

   private static enum RespawnStage {
      START,
      PREPARING_TO_SUMMON_PILLARS,
      SUMMONING_PILLARS,
      SUMMONING_DRAGON;
   }
}
