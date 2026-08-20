package com.oneworld.worldgen;

import com.mojang.serialization.MapCodec;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate.Sampler;

public final class ZoneBiomeSource extends BiomeSource {
   private final BiomeSource overworld;
   private final BiomeSource nether;
   private final BiomeSource end;

   public ZoneBiomeSource(BiomeSource overworld, BiomeSource nether, BiomeSource end) {
      this.overworld = overworld;
      this.nether = nether;
      this.end = end;
   }

   protected MapCodec<? extends BiomeSource> codec() {
      return MapCodec.unit(this);
   }

   protected Stream<Holder<Biome>> collectPossibleBiomes() {
      return Stream.concat(this.overworld.possibleBiomes().stream(), Stream.concat(this.nether.possibleBiomes().stream(), this.end.possibleBiomes().stream()));
   }

   public Set<Holder<Biome>> possibleBiomes() {
      return super.possibleBiomes();
   }

   public Holder<Biome> getNoiseBiome(int x, int y, int z, Sampler sampler) {
      int worldY = y << 2;
      if (worldY <= -193) {
         return this.nether.getNoiseBiome(x, y, z, sampler);
      } else {
         return worldY >= 1000 ? this.end.getNoiseBiome(x, y, z, sampler) : this.overworld.getNoiseBiome(x, y, z, sampler);
      }
   }
}
