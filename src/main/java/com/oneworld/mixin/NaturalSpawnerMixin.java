package com.oneworld.mixin;

import com.oneworld.OneWorld;
import com.oneworld.worldgen.BandHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({NaturalSpawner.class})
public abstract class NaturalSpawnerMixin {
   private static final RandomSource PICK_RANDOM = RandomSource.create();

   @Inject(
      method = {"getRandomPosWithin"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private static void oneworld$bandSpawnY(Level level, LevelChunk chunk, CallbackInfoReturnable<BlockPos> cir) {
      if (level.dimension() == Level.OVERWORLD && !BandHeights.isActivated()) {
         BlockPos vanilla = (BlockPos)cir.getReturnValue();
         int x = vanilla.getX();
         int z = vanilla.getZ();
         ServerPlayer nearest = null;
         double best = Double.MAX_VALUE;

         for (Player p : level.players()) {
            if (p instanceof ServerPlayer player) {
               double dist = (player.getX() - x) * (player.getX() - x) + (player.getZ() - z) * (player.getZ() - z);
               if (dist < best) {
                  best = dist;
                  nearest = player;
               }
            }
         }

         if (nearest != null) {
            // 与原版下界顶一致的行为：
            // 刷怪点钳制在最近玩家所在带 ±64 内，后续交给原版刷怪检查（脚下实心方块 + 光照）。
            // 空旷区头顶被灼热层遮挡天光为 0，配合 MonsterSpawnLightMixin 的方块光 <= 7，光照规则与原版下界一致。
            int[] band = BandHeights.bandFor(nearest.getY());
            int lo = Mth.clamp((int)Math.floor(nearest.getY()) - 64, band[0], band[1]);
            int hi = Mth.clamp((int)Math.ceil(nearest.getY()) + 64, band[0], band[1]);
            int y = Mth.randomBetweenInclusive(PICK_RANDOM, lo, hi);
            // 原版下界顶盖是基岩（isValidSpawn = never，不可刷新生），所以裸顶上永不刷怪；
            // 本模组顶盖替换成了下界岩（可刷新生），这里对等复刻：
            // 仅禁止 y = -880（正下 -881 为下界岩顶盖）这一层的刷怪点位，
            // -879 以上（含玩家搭建的平台）与下界带内部全部保持原版逻辑，刷怪机不受影响。
            if (y == OneWorld.NETHER_GAP_MIN_Y && chunk.getBlockState(new BlockPos(x, y - 1, z)).is(Blocks.NETHERRACK)) {
               cir.setReturnValue(new BlockPos(x, Integer.MIN_VALUE, z));
            } else {
               cir.setReturnValue(new BlockPos(x, y, z));
            }
         }
      }
   }
}
