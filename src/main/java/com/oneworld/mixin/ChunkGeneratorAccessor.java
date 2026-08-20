package com.oneworld.mixin;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.level.biome.FeatureSorter.StepFeatureData;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin({ChunkGenerator.class})
public interface ChunkGeneratorAccessor {
   @Accessor("featuresPerStep")
   Supplier<List<StepFeatureData>> oneworld$getFeaturesPerStep();
}
