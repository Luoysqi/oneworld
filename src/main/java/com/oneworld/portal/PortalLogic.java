package com.oneworld.portal;

import com.oneworld.OneWorld;
import com.oneworld.mixin.ServerPlayerAccessor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public final class PortalLogic {
   private static final Map<Long, BlockPos> PORTAL_CACHE = new ConcurrentHashMap<>();

   private PortalLogic() {
   }

   public static void clearCache() {
      PORTAL_CACHE.clear();
   }

   public static TeleportTransition netherPortal(ServerLevel level, Entity entity, BlockPos portalPos) {
      double x = entity.getX();
      double z = entity.getZ();
      float yaw = entity.getYRot();
      float pitch = entity.getXRot();
      boolean toNether = !OneWorld.inNetherZone(entity.getY());
      int tx;
      int tz;
      int minY;
      int maxY;
      int fallbackY;
      if (toNether) {
         tx = (int)Math.floor(x / 8.0);
         tz = (int)Math.floor(z / 8.0);
         minY = -1008;
         maxY = -881;
         fallbackY = -960;
      } else {
         tx = Mth.clamp((int)Math.floor(x * 8.0), -29999968, 29999968);
         tz = Mth.clamp((int)Math.floor(z * 8.0), -29999968, 29999968);
         minY = -63;
         maxY = 319;
         fallbackY = Math.min(Mth.clamp(level.getHeight(Types.MOTION_BLOCKING, tx, tz), minY, maxY), maxY);
      }

      long anchor = anchor(tx, tz, toNether);
      BlockPos cached = PORTAL_CACHE.get(anchor);
      if (cached != null && portalStillLit(level, cached)) {
         recordNetherTravel(level, entity, cached, toNether);
         return inFront(level, cached, yaw, pitch);
      } else {
         level.getChunk(tx >> 4, tz >> 4);
         BlockPos existing = findNearbyPortal(level, tx, tz, minY, maxY);
         if (existing != null && arrivalFree(level, existing)) {
            PORTAL_CACHE.put(anchor, existing);
            recordNetherTravel(level, entity, existing, toNether);
            return inFront(level, existing, yaw, pitch);
         } else {
            int y = findSafeSpot(level, tx, tz, minY, maxY);
            if (y == Integer.MIN_VALUE) {
               y = fallbackY;
            }

            BlockPos base = new BlockPos(tx, y, tz);
            buildObsidianPortal(level, tx, y, tz);
            PORTAL_CACHE.put(anchor, base);
            recordNetherTravel(level, entity, base, toNether);
            return inFront(level, base, yaw, pitch);
         }
      }
   }

   private static BlockPos findNearbyPortal(ServerLevel level, int cx, int cz, int minY, int maxY) {
      level.getChunk(cx - 16 >> 4, cz - 16 >> 4);
      level.getChunk(cx + 16 >> 4, cz + 16 >> 4);
      BlockPos found = null;
      double bestDist = Double.MAX_VALUE;
      MutableBlockPos pos = new MutableBlockPos();

      for (int dx = -16; dx <= 16; dx++) {
         for (int dz = -16; dz <= 16; dz++) {
            int x = cx + dx;
            int z = cz + dz;

            for (int y = minY; y <= maxY; y++) {
               pos.set(x, y, z);
               if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
                  double dist = (double)dx * dx + (double)dz * dz;
                  if (dist < bestDist) {
                     bestDist = dist;
                     found = pos.immutable();
                  }
                  break;
               }
            }
         }
      }

      while (found != null && level.getBlockState(found.below()).is(Blocks.NETHER_PORTAL)) {
         found = found.below();
      }

      if (found != null) {
         if (level.getBlockState(found).getValue(NetherPortalBlock.AXIS) == Axis.X) {
            while (level.getBlockState(found.west()).is(Blocks.NETHER_PORTAL)) {
               found = found.west();
            }
         } else {
            while (level.getBlockState(found.north()).is(Blocks.NETHER_PORTAL)) {
               found = found.north();
            }
         }
      }

      return found;
   }

   private static void recordNetherTravel(ServerLevel level, Entity entity, BlockPos arrival, boolean toNether) {
      if (entity instanceof ServerPlayer player) {
         ServerPlayerAccessor accessor = (ServerPlayerAccessor)player;
         if (toNether) {
            accessor.oneworld$setEnteredNetherPosition(new Vec3(arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5));
         } else {
            Vec3 entered = accessor.oneworld$getEnteredNetherPosition();
            if (entered != null) {
               CriteriaTriggers.NETHER_TRAVEL.trigger(player, entered);
               accessor.oneworld$setEnteredNetherPosition(null);
            }
         }
      }
   }

   public static TeleportTransition endPortal(ServerLevel level, Entity entity, BlockPos portalPos) {
      float yaw = entity.getYRot();
      if (!OneWorld.inEndZone(entity.getY())) {
         BlockPos base = new BlockPos(100, 1050, 0);
         level.getChunk(base.getX() >> 4, base.getZ() >> 4);
         EndPlatformFeature.createEndPlatform(level, base, false);
         return new TeleportTransition(
            level, new Vec3(base.getX() + 0.5, base.getY(), base.getZ() + 0.5), Vec3.ZERO, yaw, entity.getXRot(), TeleportTransition.PLAY_PORTAL_SOUND
         );
      } else {
         BlockPos spawn = level.getLevelData().getRespawnData().pos();
         int y = findSafeSpot(level, spawn.getX(), spawn.getZ(), -63, 319);
         if (y == Integer.MIN_VALUE) {
            y = Mth.clamp(level.getHeight(Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()), -63, 319);
         }

         return new TeleportTransition(
            level, new Vec3(spawn.getX() + 0.5, y, spawn.getZ() + 0.5), Vec3.ZERO, yaw, entity.getXRot(), TeleportTransition.PLAY_PORTAL_SOUND
         );
      }
   }

   private static long anchor(int x, int z, boolean netherSide) {
      return (x >> 5 & 67108863L) << 38 | (z >> 5 & 67108863L) << 6 | (netherSide ? 1L : 0L);
   }

   private static boolean arrivalFree(ServerLevel level, BlockPos base) {
      BlockPos at = base.east().south();
      return !level.getBlockState(at).blocksMotion() && !level.getBlockState(at.above()).blocksMotion();
   }

   private static boolean portalStillLit(ServerLevel level, BlockPos base) {
      BlockState a = level.getBlockState(base);
      BlockState b = level.getBlockState(base.above());
      BlockState c = level.getBlockState(base.east());
      return a.is(Blocks.NETHER_PORTAL) || b.is(Blocks.NETHER_PORTAL) || c.is(Blocks.NETHER_PORTAL);
   }

   private static TeleportTransition inFront(ServerLevel level, BlockPos base, float yaw, float pitch) {
      return new TeleportTransition(
         level, new Vec3(base.getX() + 1.0, base.getY(), base.getZ() + 1.5), Vec3.ZERO, 180.0F, pitch, TeleportTransition.PLAY_PORTAL_SOUND
      );
   }

   private static void buildObsidianPortal(ServerLevel level, int x, int y, int z) {
      MutableBlockPos pos = new MutableBlockPos();

      for (int i = -1; i <= 2; i++) {
         for (int dz = -1; dz <= 1; dz++) {
            pos.set(x + i, y - 1, z + dz);
            if (!level.getBlockState(pos).blocksMotion()) {
               level.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), 3);
            }
         }
      }

      for (int i = -1; i <= 2; i++) {
         for (int j = -1; j <= 3; j++) {
            if (i == -1 || i == 2 || j == -1 || j == 3) {
               pos.set(x + i, y + j, z);
               level.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), 3);
            }
         }
      }

      for (int i = 0; i <= 1; i++) {
         for (int jx = 0; jx <= 2; jx++) {
            for (int dzx = -1; dzx <= 1; dzx += 2) {
               pos.set(x + i, y + jx, z + dzx);
               if (level.getBlockState(pos).blocksMotion()) {
                  level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
               }
            }
         }
      }

      BlockState portal = (BlockState)Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Axis.X);

      for (int i = 0; i <= 1; i++) {
         for (int jx = 0; jx <= 2; jx++) {
            pos.set(x + i, y + jx, z);
            level.setBlock(pos, portal, 3);
         }
      }
   }

   private static int findSafeSpot(ServerLevel level, int x, int z, int minY, int maxY) {
      MutableBlockPos pos = new MutableBlockPos(x, 0, z);

      for (int y = maxY; y >= minY; y--) {
         pos.setY(y);
         BlockState feet = level.getBlockState(pos);
         if (feet.isAir()) {
            pos.setY(y - 1);
            BlockState ground = level.getBlockState(pos);
            pos.setY(y + 1);
            BlockState head = level.getBlockState(pos);
            if (!ground.isAir() && ground.blocksMotion() && head.isAir() && !ground.is(Blocks.MAGMA_BLOCK) && !ground.is(Blocks.CACTUS)) {
               return y;
            }
         }
      }

      return Integer.MIN_VALUE;
   }
}
