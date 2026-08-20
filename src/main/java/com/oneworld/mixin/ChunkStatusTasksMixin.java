package com.oneworld.mixin;

import com.oneworld.worldgen.MergedChunkGenerator;
import java.util.Set;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({ChunkStatusTasks.class})
public abstract class ChunkStatusTasksMixin {
   @Redirect(
      method = {"generateFeatures"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/levelgen/Heightmap;primeHeightmaps(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/Set;)V"
      )
   )
   private static void oneworld$featuresPrime(
      ChunkAccess chunk, Set<Types> types, WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunkArg
   ) {
      if (context.generator() instanceof MergedChunkGenerator merged) {
         merged.oneworld$primeFeatureHeightmaps(chunk, types);
      } else {
         Heightmap.primeHeightmaps(chunk, types);
      }
   }
}
