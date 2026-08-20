package com.oneworld.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin({ChunkSkyLightSources.class})
public abstract class ChunkSkyLightSourcesMixin {
   @Shadow
   @Final
   private int minY;
   @Shadow
   @Final
   private MutableBlockPos mutablePos1;
   @Shadow
   @Final
   private MutableBlockPos mutablePos2;

   @Shadow
   private void set(int index, int value) {
      throw new AssertionError();
   }

   @Overwrite
   private int findLowestSourceY(ChunkAccess chunk, int topSectionIndex, int x, int z) {
      int topY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(topSectionIndex) + 1);
      MutableBlockPos topPos = this.mutablePos1.set(x, topY, z);
      MutableBlockPos bottomPos = this.mutablePos2.setWithOffset(topPos, Direction.DOWN);
      BlockState topState = Blocks.AIR.defaultBlockState();

      for (int sectionIndex = topSectionIndex; sectionIndex >= 0; sectionIndex--) {
         LevelChunkSection section = chunk.getSection(sectionIndex);
         if (section.hasOnlyAir()) {
            topState = Blocks.AIR.defaultBlockState();
            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            topPos.setY(SectionPos.sectionToBlockCoord(sectionY));
            bottomPos.setY(topPos.getY() - 1);
         } else {
            for (int y = 15; y >= 0; y--) {
               BlockState bottomState = section.getBlockState(x, y, z);
               if (topPos.getY() < 320 && oneworld$isEdgeOccluded(topState, bottomState)) {
                  return topPos.getY();
               }

               topState = bottomState;
               topPos.set(bottomPos);
               bottomPos.move(Direction.DOWN);
            }
         }
      }

      return this.minY;
   }

   @Overwrite
   private boolean updateEdge(BlockGetter level, int index, int oldTopEdgeY, BlockPos topPos, BlockState topState, BlockPos bottomPos, BlockState bottomState) {
      int checkedEdgeY = topPos.getY();
      if (checkedEdgeY < 320 && oneworld$isEdgeOccluded(topState, bottomState)) {
         if (checkedEdgeY > oldTopEdgeY) {
            this.set(index, checkedEdgeY);
            return true;
         }
      } else if (checkedEdgeY == oldTopEdgeY) {
         this.set(index, this.findLowestSourceBelow(level, bottomPos, bottomState));
         return true;
      }

      return false;
   }

   @Overwrite
   private int findLowestSourceBelow(BlockGetter level, BlockPos startPos, BlockState startState) {
      MutableBlockPos topPos = new MutableBlockPos().set(startPos);
      MutableBlockPos bottomPos = new MutableBlockPos(startPos.getX(), startPos.getY() - 1, startPos.getZ());
      BlockState topState = startState;

      while (bottomPos.getY() >= this.minY) {
         BlockState bottomState = level.getBlockState(bottomPos);
         if (topPos.getY() < 320 && oneworld$isEdgeOccluded(topState, bottomState)) {
            return topPos.getY();
         }

         topState = bottomState;
         topPos.set(bottomPos);
         bottomPos.move(Direction.DOWN);
      }

      return this.minY;
   }

   private static boolean oneworld$isEdgeOccluded(BlockState topState, BlockState bottomState) {
      if (bottomState.getLightDampening() != 0) {
         return true;
      } else {
         VoxelShape topShape = LightEngine.getOcclusionShape(topState, Direction.DOWN);
         VoxelShape bottomShape = LightEngine.getOcclusionShape(bottomState, Direction.UP);
         return Shapes.faceShapeOccludes(topShape, bottomShape);
      }
   }
}
