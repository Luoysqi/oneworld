package com.oneworld.dragon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.EndFeatures;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.EndGatewayConfiguration;
import net.minecraft.world.phys.Vec3;

public final class EndBandGateways {
   private EndBandGateways() {
   }

   public static BlockPos findOrCreateValidTeleportPos(ServerLevel level, BlockPos gatewayPos) {
      Vec3 tentative = findExitPortalXZPosTentative(level, gatewayPos);
      ChunkAccess chunk = level.getChunk(Mth.floor(tentative.x / 16.0), Mth.floor(tentative.z / 16.0));
      BlockPos exit = findValidSpawnInChunk(chunk);
      if (exit == null) {
         BlockPos fallback = BlockPos.containing(tentative.x + 0.5, 1075.0, tentative.z + 0.5);
         level.registryAccess()
            .lookupOrThrow(Registries.CONFIGURED_FEATURE)
            .get(EndFeatures.END_ISLAND)
            .ifPresent(
               endIsland -> ((ConfiguredFeature)endIsland.value())
                  .place(level, level.getChunkSource().getGenerator(), RandomSource.create(fallback.asLong()), fallback)
            );
         exit = fallback;
      }

      return findTallestBlock(level, exit, 16, true);
   }

   private static Vec3 findExitPortalXZPosTentative(ServerLevel level, BlockPos gatewayPos) {
      Vec3 dir = new Vec3(gatewayPos.getX(), 0.0, gatewayPos.getZ());
      if (dir.lengthSqr() < 1.0E-4) {
         dir = new Vec3(1.0, 0.0, 0.0);
      }

      dir = dir.normalize();
      Vec3 tentative = dir.scale(1024.0);
      int chunkLimit = 16;

      while (!isBandChunkEmpty(level, tentative) && chunkLimit-- > 0) {
         tentative = tentative.add(dir.scale(-16.0));
      }

      chunkLimit = 16;

      while (isBandChunkEmpty(level, tentative) && chunkLimit-- > 0) {
         tentative = tentative.add(dir.scale(16.0));
      }

      return tentative;
   }

   private static boolean isBandChunkEmpty(ServerLevel level, Vec3 xzPos) {
      ChunkPos pos = new ChunkPos(Mth.floor(xzPos.x / 16.0), Mth.floor(xzPos.z / 16.0));
      ChunkAccess chunk = level.getChunk(pos.x(), pos.z());
      int minSec = chunk.getSectionIndex(1000);
      int maxSec = chunk.getSectionIndex(1127);

      for (int s = minSec; s <= maxSec; s++) {
         if (!chunk.getSection(s).hasOnlyAir()) {
            return false;
         }
      }

      return true;
   }

   private static BlockPos findValidSpawnInChunk(ChunkAccess chunk) {
      ChunkPos pos = chunk.getPos();
      BlockPos start = new BlockPos(pos.getMinBlockX(), 1000, pos.getMinBlockZ());
      BlockPos end = new BlockPos(pos.getMaxBlockX(), 1127, pos.getMaxBlockZ());
      BlockPos closest = null;
      double closestDist = 0.0;

      for (BlockPos p : BlockPos.betweenClosed(start, end)) {
         BlockState state = chunk.getBlockState(p);
         BlockPos above = p.above();
         BlockPos above2 = p.above(2);
         if (state.is(Blocks.END_STONE)
            && !chunk.getBlockState(above).isCollisionShapeFullBlock(chunk, above)
            && !chunk.getBlockState(above2).isCollisionShapeFullBlock(chunk, above2)) {
            double dist = p.distToCenterSqr(0.5, 0.5, 0.5);
            if (closest == null || dist < closestDist) {
               closest = p.immutable();
               closestDist = dist;
            }
         }
      }

      return closest;
   }

   public static BlockPos findTallestBlock(ServerLevel level, BlockPos around, int dist, boolean allowBedrock) {
      BlockPos tallest = null;

      for (int xd = -dist; xd <= dist; xd++) {
         for (int zd = -dist; zd <= dist; zd++) {
            if (xd != 0 || zd != 0 || allowBedrock) {
               int from = tallest == null ? 1000 : tallest.getY();

               for (int y = 1127; y > from; y--) {
                  BlockPos p = new BlockPos(around.getX() + xd, y, around.getZ() + zd);
                  BlockState state = level.getBlockState(p);
                  if (state.isCollisionShapeFullBlock(level, p) && (allowBedrock || !state.is(Blocks.BEDROCK))) {
                     tallest = p;
                     break;
                  }
               }
            }
         }
      }

      return tallest == null ? around : tallest;
   }

   public static BlockPos findExitPosition(ServerLevel level, BlockPos exitPortal) {
      BlockPos pos = findTallestBlock(level, exitPortal.offset(0, 2, 0), 5, false);
      return pos.above();
   }

   public static void spawnGatewayPortal(ServerLevel level, BlockPos portalPos, EndGatewayConfiguration config) {
      Feature.END_GATEWAY.place(config, level, level.getChunkSource().getGenerator(), RandomSource.create(), portalPos);
   }

   public static boolean placeDelayedGateway(ServerLevel level, BlockPos pos) {
      return level.registryAccess()
         .lookupOrThrow(Registries.CONFIGURED_FEATURE)
         .get(EndFeatures.END_GATEWAY_DELAYED)
         .map(holder -> ((ConfiguredFeature)holder.value()).place(level, level.getChunkSource().getGenerator(), RandomSource.create(), pos))
         .orElse(false);
   }
}
