package com.oneworld.mixin;

import com.oneworld.worldgen.BandHeights;
import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({ChunkAccess.class})
public abstract class ChunkAccessMixin {
   @Inject(
      method = {"getHeight"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void oneworld$bandClampHeight(Types type, int x, int z, CallbackInfoReturnable<Integer> cir) {
      int vanilla = (Integer)cir.getReturnValue();
      if (BandHeights.needsClamp(vanilla)) {
         ChunkAccess self = (ChunkAccess)(Object)this;
         Predicate<BlockState> opaque = type.isOpaque();
         int bandMinY = BandHeights.bandMinY();
         int bandMaxY = BandHeights.bandMaxY();
         int minY = self.getMinY();
         int lx = x & 15;
         int lz = z & 15;
         LevelChunkSection[] sections = self.getSections();

         for (int secIdx = self.getSectionIndex(bandMaxY); secIdx >= self.getSectionIndex(bandMinY); secIdx--) {
            LevelChunkSection sec = sections[secIdx];
            if (!sec.hasOnlyAir()) {
               int secBottom = minY + secIdx * 16;
               int startLy = Math.min(15, bandMaxY - secBottom);
               int endLy = Math.max(0, bandMinY - secBottom);

               for (int ly = startLy; ly >= endLy; ly--) {
                  if (opaque.test(sec.getBlockState(lx, ly, lz))) {
                     cir.setReturnValue(secBottom + ly);
                     return;
                  }
               }
            }
         }

         cir.setReturnValue(bandMinY - 1);
      }
   }
}
