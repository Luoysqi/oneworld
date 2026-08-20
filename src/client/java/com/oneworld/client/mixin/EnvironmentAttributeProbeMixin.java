package com.oneworld.client.mixin;

import com.oneworld.OneWorld;
import com.oneworld.client.EndSkyState;
import net.minecraft.sounds.Musics;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.BackgroundMusic;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({EnvironmentAttributeProbe.class})
public abstract class EnvironmentAttributeProbeMixin {
   @Shadow
   @Nullable
   private Level level;
   @Shadow
   @Nullable
   private Vec3 position;
   @Shadow
   @Final
   private SpatialAttributeInterpolator biomeInterpolator;
   private static final int END_SKY = -16054508;
   private static final int END_FOG = -15199464;
   private static final int END_SKY_LIGHT_COLOR = -5480243;
   private static final int END_AMBIENT = -12630209;
   // 原版末地维度背景音乐（the_end.json：default = music.end，6000..24000，replace，无创造/水下分支）
   private static final BackgroundMusic END_BACKGROUND_MUSIC = new BackgroundMusic(Musics.END);
   // 原版下界维度环境光（the_nether.json: minecraft:visual/ambient_light_color #302821）
   private static final int NETHER_AMBIENT = -13621215;
   // 原版下界维度雾距（the_nether.json: fog_start_distance 10 / fog_end_distance 96）
   private static final float NETHER_FOG_START = 10.0F;
   private static final float NETHER_FOG_END = 96.0F;

   @Inject(
      method = {"getValue"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private <Value> void oneworld$endAtmosphere(EnvironmentAttribute<Value> attribute, float partialTicks, CallbackInfoReturnable<Value> cir) {
      Level level = this.level;
      Vec3 position = this.position;
      if (level != null && position != null && level.dimension() == Level.OVERWORLD) {
         // 末地带背景音乐：原版末地音乐定义在维度级（the_end.json），末地群系自身不带音乐属性，
         // 合并维度的维度级音乐是主世界的 music.game，若不覆盖，末地带会一直播放主世界音乐。
         // 下界带无需处理：原版 5 个下界群系各自带有 background_music（位置化），回落机制天然正确。
         if (position.y >= 1000.0 && attribute == EnvironmentAttributes.BACKGROUND_MUSIC) {
            cir.setReturnValue((Value)END_BACKGROUND_MUSIC);
            return;
         }

         float nether = EndSkyState.netherFactorFor(position.y);
         if (nether > 0.0F) {
            Value netherValue = this.oneworld$netherAtmosphere(level, position, attribute, cir.getReturnValue(), nether);
            if (netherValue != null) {
               cir.setReturnValue(netherValue);
               return;
            }
         }

         float factor = EndSkyState.factorFor(position.y);
         if (factor > 0.0F) {
            Value value = cir.getReturnValue();
            if (attribute == EnvironmentAttributes.SUNRISE_SUNSET_COLOR && value instanceof Integer color) {
               float alpha = ARGB.alphaFloat(color) * (1.0F - factor);
               cir.setReturnValue((Value)Integer.valueOf(ARGB.color(alpha, color)));
            } else if (attribute == EnvironmentAttributes.SKY_COLOR && value instanceof Integer color) {
               cir.setReturnValue((Value)Integer.valueOf(ARGB.srgbLerp(factor, color, END_SKY)));
            } else if (attribute == EnvironmentAttributes.FOG_COLOR && value instanceof Integer color) {
               cir.setReturnValue((Value)Integer.valueOf(ARGB.srgbLerp(factor, color, END_FOG)));
            } else if (attribute == EnvironmentAttributes.SKY_LIGHT_COLOR && value instanceof Integer color) {
               cir.setReturnValue((Value)Integer.valueOf(ARGB.srgbLerp(factor, color, END_SKY_LIGHT_COLOR)));
            } else if (attribute == EnvironmentAttributes.AMBIENT_LIGHT_COLOR && value instanceof Integer color) {
               cir.setReturnValue((Value)Integer.valueOf(ARGB.srgbLerp(factor, color, END_AMBIENT)));
            } else if (attribute == EnvironmentAttributes.SKY_LIGHT_FACTOR && value instanceof Float skyFactor) {
               cir.setReturnValue((Value)Float.valueOf(skyFactor * (1.0F - factor)));
            } else if (attribute == EnvironmentAttributes.STAR_BRIGHTNESS && value instanceof Float stars) {
               cir.setReturnValue((Value)Float.valueOf(Math.max(stars, 0.5F * factor)));
            }
         }
      }
   }

   // 下界氛围完全对齐原版：
   // - 雾色按群系：原版每个下界群系在自身 attributes 里定义 fog_color（灵魂沙峡谷 #1b4745、诡异森林 #1a051a、
   //   玄武岩三角洲、绯红森林、下界荒地各不相同）。y <= 下界缺口底部时相机处的群系已经是下界群系，
   //   原版探针的返回值本身就是正确的逐群系插值雾色，不再覆盖；灼热层过渡区则向正下方第一个下界群系的雾色渐变
   // - 天空色：原版下界 skybox=none（没有天空），把天空色融进当前群系的雾色
   // - 环境光与雾距：用原版下界维度值（#302821 / 10 / 96）
   private <Value> Value oneworld$netherAtmosphere(Level level, Vec3 pos, EnvironmentAttribute<Value> attribute, Value value, float nether) {
      if (attribute == EnvironmentAttributes.AMBIENT_LIGHT_COLOR && value instanceof Integer color) {
         return (Value)Integer.valueOf(ARGB.srgbLerp(nether, color, NETHER_AMBIENT));
      } else if (attribute == EnvironmentAttributes.FOG_COLOR && value instanceof Integer color) {
         if (pos.y <= (double)OneWorld.NETHER_GAP_MAX_Y) {
            return null;
         }

         Integer target = this.oneworld$netherFogColor(level, pos);
         return target != null ? (Value)Integer.valueOf(ARGB.srgbLerp(nether, color, target)) : null;
      } else if (attribute == EnvironmentAttributes.SKY_COLOR && value instanceof Integer color) {
         Integer target = this.oneworld$netherFogColor(level, pos);
         return target != null ? (Value)Integer.valueOf(ARGB.srgbLerp(nether, color, target)) : null;
      } else if (attribute == EnvironmentAttributes.FOG_START_DISTANCE && value instanceof Float start) {
         return (Value)Float.valueOf(Mth.lerp(nether, start, NETHER_FOG_START));
      } else if (attribute == EnvironmentAttributes.FOG_END_DISTANCE && value instanceof Float end) {
         return (Value)Float.valueOf(Mth.lerp(nether, end, NETHER_FOG_END));
      } else if (attribute == EnvironmentAttributes.SUNRISE_SUNSET_COLOR && value instanceof Integer color) {
         return (Value)Integer.valueOf(ARGB.color(ARGB.alphaFloat(color) * (1.0F - nether), color));
      } else if (attribute == EnvironmentAttributes.SKY_LIGHT_FACTOR && value instanceof Float skyFactor) {
         return (Value)Float.valueOf(skyFactor * (1.0F - nether));
      } else {
         return attribute == EnvironmentAttributes.STAR_BRIGHTNESS && value instanceof Float stars
            ? (Value)Float.valueOf(stars * (1.0F - nether))
            : null;
      }
   }

   // 当前位置的下界雾色：
   // 已进入下界场景（y <= 下界缺口底部）→ 直接查原版环境属性系统（带探针的群系插值器，逐群系插值结果）；
   // 过渡区（灼热层，相机群系仍是主世界群系）→ 采样正下方第一个下界群系（缺口顶段的群系）作为渐变目标
   private Integer oneworld$netherFogColor(Level level, Vec3 pos) {
      if (pos.y <= (double)OneWorld.NETHER_GAP_MAX_Y) {
         return level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, pos, this.biomeInterpolator);
      } else {
         Vec3 below = new Vec3(pos.x, OneWorld.NETHER_GAP_MAX_Y - 4.0, pos.z);
         return level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, below, null);
      }
   }
}
