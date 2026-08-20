package com.oneworld.defense;

import com.oneworld.OneWorld;
import com.oneworld.OneWorldConfig;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SkyDefenseHandler {
   public static final String SKY_TAG = "oneworld_sky";
   public static final String ELITE_TAG = "oneworld_elite";
   private static final int[] TIER_SIZE = new int[]{0, 2, 4, 2};
   private static int tickCounter = 0;
   private static final RandomSource RANDOM = RandomSource.create();

   static {
      // 死亡记账：任何天空幻翼死亡都计入其所属玩家的档位击杀数（与 OneWorld 里的掉落事件互不冲突）
      ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
         if (entity.level() instanceof ServerLevel serverLevel && entity instanceof Phantom phantom && phantom.entityTags().contains("oneworld_sky")) {
            onPhantomDeath(serverLevel, phantom);
         }
      });
   }

   private SkyDefenseHandler() {
   }

   public static void reset() {
      tickCounter = 0;
   }

   public static void tickServer(MinecraftServer server) {
      tickCounter++;
      ServerLevel level = server.overworld();
      if (level != null) {
         if (level.getDifficulty() == Difficulty.PEACEFUL) {
            for (Phantom phantom : level.getEntities(EntityTypes.PHANTOM, p -> p.isAlive() && p.entityTags().contains("oneworld_sky") && p.getTarget() != null)) {
               phantom.setTarget(null);
            }
         }

         if (tickCounter % 20 == 0) {
            if (!OneWorldConfig.restrictionsEnabled()) {
               for (Phantom phantom : level.getEntities(EntityTypes.PHANTOM, p -> p.isAlive() && p.entityTags().contains("oneworld_sky"))) {
                  phantom.discard();
               }

               for (ServerPlayer player : level.players()) {
                  ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_TIER_KILLS, 0);
                  ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_ACTIVE_TIER, 0);
                  ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_SQUAD_ALIVE, 0);
               }
            } else {
               for (Phantom phantom : level.getEntities(EntityTypes.PHANTOM, p -> p.isAlive() && p.entityTags().contains("oneworld_sky"))) {
                  confineToSkyGap(phantom);
                  if (phantom.entityTags().contains("oneworld_elite")) {
                     smashByBody(level, phantom);
                  }
               }

               for (ServerPlayer player : level.players()) {
                  tickPlayer(level, player);
               }
            }
         }
      }
   }

   public static void tickPlayer(ServerLevel level, ServerPlayer player) {
      double y = player.getY();
      if (y >= 800.0 && y < 1000.0) {
         AdvancementHelper.award(player, "physical_anti_air_defense");
      }

      if (y < 560.0) {
         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_WARNED_AT, 0L);
      } else if (y < 680.0) {
         long dayTime = level.getGameTime();
         long last = OneWorldAttachments.getLong(player, OneWorldAttachments.SKY_WARNED_AT);
         if (dayTime - last > 120L) {
            ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_WARNED_AT, dayTime);
            level.playSound(null, player.blockPosition(), SoundEvents.PHANTOM_AMBIENT, SoundSource.HOSTILE, 1.6F, 0.7F);
         }
      }

      int activeTier = OneWorldAttachments.getInt(player, OneWorldAttachments.SKY_ACTIVE_TIER);
      int tier = tierFor(y, activeTier);
      if (tier != activeTier) {
         // 换带：旧档活着的幻翼撤退（未死的可再登场），被杀的不复活
         retireActiveSquad(level, player);
         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_ACTIVE_TIER, tier);
         if (tier >= 1 && tier <= 3) {
            int count = remainingTier(player, tier);
            if (count > 0) {
               deployTier(level, player, tier, count);
            }
         }
      } else if (tier >= 1 && tier <= 3) {
         tickActiveTier(level, player, tier);
      }

      if (tier == 0) {
         maybeResetRound(player);
      }
   }

   // 档位判定：0=防空区下方 1/2/3=三档防空带 4=末地带；带间 ±12 格缓冲防抖
   private static int tierFor(double y, int activeTier) {
      if (y >= 1000.0) {
         return 4;
      } else if (activeTier == 4 && y >= 988.0) {
         return 4;
      } else {
         switch (activeTier) {
            case 1:
               return y < 668.0 ? 0 : (y >= 792.0 ? 2 : 1);
            case 2:
               return y < 768.0 ? 1 : (y >= 892.0 ? 3 : 2);
            case 3:
               return y < 868.0 ? 2 : 3;
            default:
               return y >= 880.0 ? 3 : (y >= 780.0 ? 2 : (y >= 680.0 ? 1 : 0));
         }
      }
   }

   private static int tierKills(ServerPlayer player, int tier) {
      if (tier < 1 || tier >= TIER_SIZE.length) {
         return 0;
      } else {
         return OneWorldAttachments.getInt(player, OneWorldAttachments.SKY_TIER_KILLS) >> (tier - 1) * 4 & 0xF;
      }
   }

   private static void addTierKill(ServerPlayer player, int tier) {
      if (tier >= 1 && tier < TIER_SIZE.length) {
         int kills = OneWorldAttachments.getInt(player, OneWorldAttachments.SKY_TIER_KILLS);
         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_TIER_KILLS, kills + (1 << (tier - 1) * 4));
      }
   }

   private static boolean isTierDead(ServerPlayer player, int tier) {
      return tier >= 1 && tier < TIER_SIZE.length && tierKills(player, tier) >= TIER_SIZE[tier];
   }

   // 本档还可登场的数量 = 编制 - 本轮已杀死数（被杀的永不复活）
   private static int remainingTier(ServerPlayer player, int tier) {
      return tier < 1 || tier >= TIER_SIZE.length ? 0 : Math.max(0, TIER_SIZE[tier] - tierKills(player, tier));
   }

   private static void tickActiveTier(ServerLevel level, ServerPlayer player, int tier) {
      if (OneWorldAttachments.getInt(player, OneWorldAttachments.SKY_SQUAD_ALIVE) <= 0) {
         int count = remainingTier(player, tier);
         if (count > 0) {
            // 首次部署失败后的重试（生成点被挡时原子回滚）
            deployTier(level, player, tier, count);
         }
      }
   }

   private static void retireActiveSquad(ServerLevel level, ServerPlayer player) {
      int activeTier = OneWorldAttachments.getInt(player, OneWorldAttachments.SKY_ACTIVE_TIER);
      if (activeTier >= 1 && activeTier <= 3) {
         List<? extends Phantom> squad = level.getEntities(
            EntityTypes.PHANTOM, p -> p.isAlive() && p.entityTags().contains("oneworld_sky") && owns(p, player)
         );
         for (Phantom phantom : squad) {
            phantom.discard();
         }

         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_SQUAD_ALIVE, 0);
      }
   }

   private static void deployTier(ServerLevel level, ServerPlayer player, int tier, int count) {
      boolean elite = tier == 3;
      List<Phantom> spawned = new ArrayList<>(count);

      for (int i = 0; i < count; i++) {
         Phantom phantom = spawnPhantom(level, player, elite);
         if (phantom == null) {
            break;
         }

         spawned.add(phantom);
         OneWorld.LOGGER
            .info(
               "[OneWorld] sky defence: spawned {} phantom for {} at ({}, {}, {})",
               new Object[]{elite ? "ELITE" : "sky", player.getName().getString(), phantom.getX(), phantom.getY(), phantom.getZ()}
            );
      }

      if (spawned.size() == count) {
         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_SQUAD_ALIVE, count);
      } else {
         // 部分生成失败：整体撤回，下一周期重试
         for (Phantom phantom : spawned) {
            phantom.discard();
         }

         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_SQUAD_ALIVE, 0);
      }
   }

   // 整轮重置的唯一条件：三档幻翼全部被杀 + 玩家掉落回防空区下方（主世界空域）
   private static void maybeResetRound(ServerPlayer player) {
      if (isTierDead(player, 1) && isTierDead(player, 2) && isTierDead(player, 3)) {
         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_TIER_KILLS, 0);
         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_ACTIVE_TIER, 0);
         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_SQUAD_ALIVE, 0);
         OneWorld.LOGGER
            .info(
               "[OneWorld] sky defence: {} wiped out all three tiers and returned below the defence line - the sky defence regroups",
               player.getName().getString()
            );
      }
   }

   private static void onPhantomDeath(ServerLevel level, Phantom phantom) {
      UUID owner = ownerUuid(phantom);
      if (owner == null) {
         return;
      }

      ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
      if (player == null) {
         return;
      }

      int alive = OneWorldAttachments.getInt(player, OneWorldAttachments.SKY_SQUAD_ALIVE) - 1;
      ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_SQUAD_ALIVE, Math.max(alive, 0));
      int activeTier = OneWorldAttachments.getInt(player, OneWorldAttachments.SKY_ACTIVE_TIER);
      if (activeTier >= 1 && activeTier <= 3) {
         addTierKill(player, activeTier);
         if (isTierDead(player, activeTier)) {
            OneWorld.LOGGER
               .info(
                  "[OneWorld] sky defence: {} wiped out the tier {} squad - this tier stays down until the round resets",
                  player.getName().getString(),
                  activeTier
               );
         }
      }
   }

   private static void confineToSkyGap(Phantom phantom) {
      double floor = 328.0;
      double ceiling = 992.0;
      double y = phantom.getY();
      if (y < floor || y > ceiling) {
         Vec3 motion = phantom.getDeltaMovement();
         phantom.setDeltaMovement(motion.x, 0.0, motion.z);
         phantom.setPos(phantom.getX(), Math.clamp(y, floor, ceiling), phantom.getZ());
      }
   }

   private static boolean owns(Phantom phantom, ServerPlayer player) {
      UUID owner = ownerUuid(phantom);
      return owner != null && owner.equals(player.getUUID());
   }

   private static UUID ownerUuid(Phantom phantom) {
      for (String tag : phantom.entityTags()) {
         if (tag.startsWith("oneworld_sky:")) {
            try {
               return UUID.fromString(tag.substring("oneworld_sky:".length()));
            } catch (IllegalArgumentException var4) {
               return null;
            }
         }
      }

      return null;
   }

   private static Phantom spawnPhantom(ServerLevel level, ServerPlayer player, boolean elite) {
      double ang = RANDOM.nextDouble() * Math.PI * 2.0;
      double dist = 10.0 + RANDOM.nextDouble() * 14.0;
      double x = player.getX() + Math.cos(ang) * dist;
      double z = player.getZ() + Math.sin(ang) * dist;
      double y = Math.min(player.getY() + 10.0 + RANDOM.nextDouble() * 14.0, 992.0);
      BlockPos spot = BlockPos.containing(x, y, z);
      if (level.getBlockState(spot).isAir() && level.getBlockState(spot.above()).isAir()) {
         Phantom phantom = (Phantom)EntityTypes.PHANTOM.create(level, EntitySpawnReason.EVENT);
         if (phantom == null) {
            return null;
         } else {
            phantom.setPos(x, y, z);
            phantom.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), EntitySpawnReason.EVENT, null);
            phantom.setPersistenceRequired();
            phantom.addTag("oneworld_sky");
            phantom.addTag("oneworld_sky:" + player.getUUID());
            if (elite) {
               phantom.addTag("oneworld_elite");
               phantom.setCustomName(Component.translatable("entity.oneworld.elite_phantom"));
               phantom.setCustomNameVisible(true);
               AttributeInstance scale = phantom.getAttribute(Attributes.SCALE);
               if (scale != null) {
                  scale.addTransientModifier(new AttributeModifier(OneWorld.id("elite_scale"), 1.0, Operation.ADD_VALUE));
               }

               AttributeInstance health = phantom.getAttribute(Attributes.MAX_HEALTH);
               if (health != null) {
                  health.addTransientModifier(new AttributeModifier(OneWorld.id("elite_health"), 2.0, Operation.ADD_MULTIPLIED_BASE));
                  phantom.setHealth(phantom.getMaxHealth());
               }

               AttributeInstance damage = phantom.getAttribute(Attributes.ATTACK_DAMAGE);
               if (damage != null) {
                  damage.addTransientModifier(new AttributeModifier(OneWorld.id("elite_damage"), 3.0, Operation.ADD_VALUE));
               }

               AttributeInstance knockback = phantom.getAttribute(Attributes.ATTACK_KNOCKBACK);
               if (knockback != null) {
                  knockback.addTransientModifier(new AttributeModifier(OneWorld.id("elite_knockback"), 3.0, Operation.ADD_VALUE));
               }
            }

            level.addFreshEntity(phantom);
            return phantom;
         }
      } else {
         return null;
      }
   }

   private static void smashByBody(ServerLevel level, Phantom phantom) {
      AABB box = phantom.getBoundingBox().inflate(0.5);

      for (BlockPos pos : BlockPos.betweenClosed(
         Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ), Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ)
      )) {
         BlockState state = level.getBlockState(pos);
         if (!state.isAir()
            && !(state.getDestroySpeed(level, pos) < 0.0F)
            && !(state.getBlock().getExplosionResistance() >= Blocks.OBSIDIAN.getExplosionResistance())) {
            level.destroyBlock(pos.immutable(), false, phantom);
         }
      }
   }

   public static void onEliteAttack(ServerLevel level, Phantom attacker, Player victim, DamageSource source) {
      if (attacker.entityTags().contains("oneworld_elite")) {
         Vec3 push = new Vec3(victim.getX() - attacker.getX(), 0.0, victim.getZ() - attacker.getZ());
         if (push.lengthSqr() < 0.01) {
            push = new Vec3(0.5, 0.0, 0.5);
         }

         push = push.normalize().scale(1.2).add(0.0, 1.1, 0.0);
         victim.setDeltaMovement(victim.getDeltaMovement().add(push));
         victim.hurtMarked = true;
      }
   }

   public static void onEliteDeath(ServerLevel level, Phantom phantom) {
      if (phantom.entityTags().contains("oneworld_elite")) {
         Vec3 at = phantom.position();

         for (int i = 0; i < 4; i++) {
            level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.PHANTOM_MEMBRANE)));
         }

         ExperienceOrb.award(level, at, 15);
      }
   }

   public static void checkFreefallAdvancement(ServerPlayer player) {
      if (OneWorldConfig.restrictionsEnabled()) {
         long hit = OneWorldAttachments.getLong(player, OneWorldAttachments.PHANTOM_HIT_AT);
         if (hit > 0L && player.level().getGameTime() - hit <= 600L && player.isInWater()) {
            AdvancementHelper.award(player, "freefall_tester");
         }
      }
   }

   public static void onPlayerDisconnect(ServerLevel level, ServerPlayer player) {
      if (OneWorldConfig.restrictionsEnabled()) {
         // 断线：活着的幻翼撤退（未死，可再登场），击杀记录保留，不整轮重置
         retireActiveSquad(level, player);
         ((AttachmentTarget)player).setAttached(OneWorldAttachments.SKY_ACTIVE_TIER, 0);
      }
   }

   public static void afterDamage(Entity entity, DamageSource source) {
      if (entity.level() instanceof ServerLevel serverLevel
         && source.getEntity() instanceof Phantom phantom
         && entity instanceof Player victim
         && phantom.entityTags().contains("oneworld_sky")) {
         if (victim instanceof ServerPlayer serverPlayer) {
            ((AttachmentTarget)serverPlayer).setAttached(OneWorldAttachments.PHANTOM_HIT_AT, serverLevel.getGameTime());
         }

         if (phantom.entityTags().contains("oneworld_elite")) {
            onEliteAttack(serverLevel, phantom, victim, source);
         }
      }
   }
}
