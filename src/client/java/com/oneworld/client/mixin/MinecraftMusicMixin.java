package com.oneworld.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({Minecraft.class})
public abstract class MinecraftMusicMixin {
   // 原版龙战 Boss 音乐（Musics.END_BOSS）的触发条件是"玩家处于 END 维度 且 Boss 血条开启音乐"
   // （Minecraft#getSituationalMusic）。合并世界的龙战在主世界维度的末地带进行，维度判断恒为假，
   // 导致打龙时永远播不出 Boss 音乐。这里把"相机位于末地带（y >= 1000）"映射为 END，
   // 让原版整条判断链（含屏幕音乐优先级）原样生效。
   @Redirect(
      method = {"getSituationalMusic"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/Level;dimension()Lnet/minecraft/resources/ResourceKey;"
      )
   )
   private ResourceKey<Level> oneworld$endBandBossMusic(Level level) {
      Minecraft self = (Minecraft)(Object)this;
      if (level.dimension() == Level.OVERWORLD && self.player != null && self.gameRenderer.mainCamera().position().y >= 1000.0) {
         return Level.END;
      } else {
         return level.dimension();
      }
   }
}
