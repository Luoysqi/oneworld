package com.oneworld.mixin;

import com.oneworld.OneWorld;
import com.oneworld.worldgen.MergedChunkGenerator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({MinecraftServer.class})
public abstract class MinecraftServerMixin {
   @Redirect(
      method = {"setInitialSpawn"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/levelgen/RandomState;sampler()Lnet/minecraft/world/level/biome/Climate$Sampler;"
      )
   )
   private static Sampler oneworld$overworldSpawnSampler(
      RandomState randomState, ServerLevel level, ServerLevelData levelData, boolean spawnBonusChest, boolean isDebug, LevelLoadListener levelLoadListener
   ) {
      if (level.dimension() == Level.OVERWORLD && level.getChunkSource().getGenerator() instanceof MergedChunkGenerator merged) {
         Sampler sampler = oneworld$mergedOverworldSampler(merged, level);
         if (sampler != null) {
            return sampler;
         }
      }

      return randomState.sampler();
   }

   private static Sampler oneworld$mergedOverworldSampler(MergedChunkGenerator merged, ServerLevel level) {
      try {
         Method prepare = MergedChunkGenerator.class.getDeclaredMethod("prepare", ServerLevel.class);
         prepare.setAccessible(true);
         prepare.invoke(merged, level);
         Field field = MergedChunkGenerator.class.getDeclaredField("overworldRandomState");
         field.setAccessible(true);
         RandomState overworld = (RandomState)field.get(merged);
         return overworld != null ? overworld.sampler() : null;
      } catch (ReflectiveOperationException var4) {
         OneWorld.LOGGER.warn("[OneWorld] could not use the overworld noise sampler for spawn selection", var4);
         return null;
      }
   }
}
