package com.oneworld.mixin;

import java.util.Map;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin({ChunkAccess.class})
public interface ChunkAccessHeightmaps {
   @Accessor("heightmaps")
   Map<Types, Heightmap> oneworld$heightmaps();

   @Accessor("noiseChunk")
   void oneworld$setNoiseChunk(NoiseChunk var1);
}
