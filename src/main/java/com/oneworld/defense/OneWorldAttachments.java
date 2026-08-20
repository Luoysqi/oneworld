package com.oneworld.defense;

import com.mojang.serialization.Codec;
import com.oneworld.OneWorld;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.world.entity.Entity;

public final class OneWorldAttachments {
   public static final AttachmentType<Long> SKY_WARNED_AT = AttachmentRegistry.createPersistent(OneWorld.id("sky_warned_at"), Codec.LONG);
   // 三档击杀记账（每 4 位一档：tier1 kills | tier2<<4 | tier3<<8）
   public static final AttachmentType<Integer> SKY_TIER_KILLS = AttachmentRegistry.createPersistent(OneWorld.id("sky_tier_kills"), Codec.INT);
   public static final AttachmentType<Integer> SKY_ACTIVE_TIER = AttachmentRegistry.createPersistent(OneWorld.id("sky_active_tier"), Codec.INT);
   public static final AttachmentType<Integer> SKY_SQUAD_ALIVE = AttachmentRegistry.createPersistent(OneWorld.id("sky_squad_alive"), Codec.INT);
   public static final AttachmentType<Long> PHANTOM_HIT_AT = AttachmentRegistry.createPersistent(OneWorld.id("phantom_hit_at"), Codec.LONG);
   public static final AttachmentType<Boolean> HEAT_IN_ZONE = AttachmentRegistry.createPersistent(OneWorld.id("heat_in_zone"), Codec.BOOL);
   public static final AttachmentType<Boolean> HEAT_BURNED = AttachmentRegistry.createPersistent(OneWorld.id("heat_burned"), Codec.BOOL);
   public static final AttachmentType<Integer> HEAT_DIGS = AttachmentRegistry.createPersistent(OneWorld.id("heat_digs"), Codec.INT);
   public static final AttachmentType<String> HEAT_DIGS_TOOL = AttachmentRegistry.createPersistent(OneWorld.id("heat_digs_tool"), Codec.STRING);
   public static final AttachmentType<Integer> HEAT_TOOL_DAMAGE = AttachmentRegistry.createPersistent(OneWorld.id("heat_tool_damage"), Codec.INT);
   public static final AttachmentType<Boolean> HEAT_WARNED_HALF = AttachmentRegistry.createPersistent(OneWorld.id("heat_warned_half"), Codec.BOOL);
   public static final AttachmentType<Boolean> HEAT_WARNED_LAST = AttachmentRegistry.createPersistent(OneWorld.id("heat_warned_last"), Codec.BOOL);
   public static final AttachmentType<Boolean> HEAT_ADV = AttachmentRegistry.createPersistent(OneWorld.id("heat_adv"), Codec.BOOL);

   private OneWorldAttachments() {
   }

   public static void register() {
   }

   public static long getLong(Entity entity, AttachmentType<Long> type) {
      Long value = (Long)((AttachmentTarget)entity).getAttached(type);
      return value == null ? 0L : value;
   }

   public static boolean getBool(Entity entity, AttachmentType<Boolean> type) {
      Boolean value = (Boolean)((AttachmentTarget)entity).getAttached(type);
      return value != null && value;
   }

   public static int getInt(Entity entity, AttachmentType<Integer> type) {
      Integer value = (Integer)((AttachmentTarget)entity).getAttached(type);
      return value == null ? 0 : value;
   }
}
