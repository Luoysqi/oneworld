package com.oneworld.mixin;

import com.oneworld.OneWorld;
import com.oneworld.worldgen.BandHeights;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({PlayerSpawnFinder.class})
public abstract class PlayerSpawnFinderMixin {
   @Inject(
      method = {"getLevelRespawnPos"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void oneworld$bandRespawnPos(ServerLevel level, int x, int z, CallbackInfoReturnable<BlockPos> cir) {
      if (level.dimension() == Level.OVERWORLD) {
         cir.setReturnValue(oneworld$findBandRespawnPos(level, x, z));
      }
   }

   private static BlockPos oneworld$findBandRespawnPos(ServerLevel level, int x, int z) {
      LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
      BandHeights.activateOverworld();

      try {
         int topY = chunk.getHeight(Types.MOTION_BLOCKING, x & 15, z & 15);
         if (topY < OneWorld.OVERWORLD_MIN_Y) {
            return null;
         }

         int surface = chunk.getHeight(Types.WORLD_SURFACE, x & 15, z & 15);
         if (surface <= topY && surface > chunk.getHeight(Types.OCEAN_FLOOR, x & 15, z & 15)) {
            return null;
         }

         MutableBlockPos pos = new MutableBlockPos();

         for (int y = topY + 1; y >= OneWorld.OVERWORLD_MIN_Y; y--) {
            pos.set(x, y, z);
            BlockState blockState = level.getBlockState(pos);
            if (!blockState.getFluidState().isEmpty()) {
               return null;
            }

            if (Block.isFaceFull(blockState.getCollisionShape(level, pos), Direction.UP)) {
               return pos.above().immutable();
            }
         }

         return null;
      } finally {
         BandHeights.deactivate();
      }
   }

   @Inject(
      method = {"fixupSpawnHeight"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void oneworld$bandFixupSpawnHeight(CollisionGetter level, BlockPos spawnPos, CallbackInfoReturnable<Vec3> cir) {
      if (level instanceof ServerLevel serverLevel && serverLevel.dimension() == Level.OVERWORLD) {
         MutableBlockPos mutablePos = spawnPos.mutable();
         mutablePos.setY(Mth.clamp(spawnPos.getY(), OneWorld.OVERWORLD_MIN_Y, OneWorld.OVERWORLD_MAX_Y));

         while (!oneworld$noCollision(level, mutablePos) && mutablePos.getY() < OneWorld.OVERWORLD_MAX_Y) {
            mutablePos.move(Direction.UP);
         }

         mutablePos.move(Direction.DOWN);

         while (oneworld$noCollision(level, mutablePos) && mutablePos.getY() > OneWorld.OVERWORLD_MIN_Y) {
            mutablePos.move(Direction.DOWN);
         }

         mutablePos.move(Direction.UP);
         cir.setReturnValue(Vec3.atBottomCenterOf(mutablePos));
      }
   }

   private static boolean oneworld$noCollision(CollisionGetter level, BlockPos pos) {
      return level.noCollision(null, EntityTypes.PLAYER.getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(pos)), true);
   }

   @Inject(
      method = {"findSpawn"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private static void oneworld$clampFindSpawn(ServerLevel level, BlockPos spawnSuggestion, CallbackInfoReturnable<CompletableFuture<Vec3>> cir) {
      if (level.dimension() == Level.OVERWORLD) {
         CompletableFuture<Vec3> original = (CompletableFuture<Vec3>)cir.getReturnValue();
         cir.setReturnValue(original.thenApply(pos -> oneworld$clamp(level, pos)));
      }
   }

   private static Vec3 oneworld$clamp(ServerLevel level, Vec3 pos) {
      int x = Mth.floor(pos.x);
      int z = Mth.floor(pos.z);
      boolean inBand = pos.y > -63.0 && pos.y < 319.0;
      if (inBand && !oneworld$isWaterColumn(level, x, z)) {
         return oneworld$freeUpward(level, pos);
      } else {
         int y;
         if (inBand) {
            y = Math.clamp((long)Mth.floor(pos.y), -62, 319);
         } else {
            BandHeights.activateOverworld();

            int top;
            try {
               top = level.getHeight(Types.MOTION_BLOCKING, x, z);
            } finally {
               BandHeights.deactivate();
            }

            y = Math.clamp((long)(top + 1), -62, 319);
            OneWorld.LOGGER.info("[OneWorld] spawn search returned {} (outside overworld band); clamped to ({}, {}, {})", new Object[]{pos, x, y, z});
         }

         return oneworld$freeUpward(level, oneworld$ensureDryLand(level, x, y, z));
      }
   }

   private static Vec3 oneworld$ensureDryLand(ServerLevel level, int x, int y, int z) {
      if (!oneworld$isWaterColumn(level, x, z)) {
         return new Vec3(x + 0.5, y, z + 0.5);
      } else {
         BandHeights.activateOverworld();

         try {
            for (int ring = 1; ring <= 8; ring++) {
               for (int dx = -ring; dx <= ring; dx++) {
                  for (int dz = -ring; dz <= ring; dz++) {
                     if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        int cx = x + dx * 16;
                        int cz = z + dz * 16;
                        int surface = level.getHeight(Types.MOTION_BLOCKING, cx, cz);
                        if (surface <= level.getHeight(Types.OCEAN_FLOOR, cx, cz)) {
                           int cy = Math.clamp((long)(surface + 1), -62, 319);
                           OneWorld.LOGGER.info("[OneWorld] spawn column ({}, {}) is water; moved to dry land at ({}, {}, {})", new Object[]{x, z, cx, cy, cz});
                           return new Vec3(cx + 0.5, cy, cz + 0.5);
                        }
                     }
                  }
               }
            }
         } finally {
            BandHeights.deactivate();
         }

         OneWorld.LOGGER.warn("[OneWorld] spawn column ({}, {}) is water and no land within 128 blocks; keeping it", x, z);
         return new Vec3(x + 0.5, y, z + 0.5);
      }
   }

   private static boolean oneworld$isWaterColumn(ServerLevel level, int x, int z) {
      BandHeights.activateOverworld();

      boolean var3;
      try {
         var3 = level.getHeight(Types.MOTION_BLOCKING, x, z) > level.getHeight(Types.OCEAN_FLOOR, x, z);
      } finally {
         BandHeights.deactivate();
      }

      return var3;
   }

   private static Vec3 oneworld$freeUpward(ServerLevel level, Vec3 pos) {
      int x = Mth.floor(pos.x);
      int z = Mth.floor(pos.z);
      int y = Mth.floor(pos.y);

      for (int guard = 0; guard++ < 64 && y < 319; y++) {
         BlockPos feet = new BlockPos(x, y, z);
         if (!level.getBlockState(feet).blocksMotion() && !level.getBlockState(feet.above()).blocksMotion()) {
            break;
         }
      }

      return new Vec3(x + 0.5, y, z + 0.5);
   }
}
