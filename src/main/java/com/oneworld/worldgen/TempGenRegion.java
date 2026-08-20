package com.oneworld.worldgen;

import com.oneworld.OneWorld;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEvent.Context;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.WorldGenTickAccess;
import org.jspecify.annotations.Nullable;

public final class TempGenRegion implements WorldGenLevel {
   private final ServerLevel level;
   private final TempZoneGenerator store;
   private final ChunkPos center;
   private final WorldGenTickAccess<Block> blockTicks = new WorldGenTickAccess(pos -> this.chunkAt((BlockPos)pos).getBlockTicks());
   private final WorldGenTickAccess<Fluid> fluidTicks = new WorldGenTickAccess(pos -> this.chunkAt((BlockPos)pos).getFluidTicks());
   private ChunkAccess cachedChunk;
   private int cachedChunkX = Integer.MIN_VALUE;
   private int cachedChunkZ;

   public TempGenRegion(ServerLevel level, TempZoneGenerator store, ChunkPos center) {
      this.level = level;
      this.store = store;
      this.center = center;
   }

   public ServerLevel getLevel() {
      return this.level;
   }

   public ChunkPos getCenter() {
      return this.center;
   }

   private ChunkAccess chunkAt(BlockPos pos) {
      return this.resolveChunk(pos.getX() >> 4, pos.getZ() >> 4);
   }

   private ChunkAccess resolveChunk(int cx, int cz) {
      ChunkAccess cached = this.cachedChunk;
      if (cached != null && this.cachedChunkX == cx && this.cachedChunkZ == cz) {
         return cached;
      } else {
         ChunkAccess resolved = this.store.chunk(cx, cz);
         this.cachedChunk = resolved;
         this.cachedChunkX = cx;
         this.cachedChunkZ = cz;
         return resolved;
      }
   }

   public int getMinY() {
      return this.store.heights().getMinY();
   }

   public int getHeight() {
      return this.store.heights().getHeight();
   }

   public long getSeed() {
      return this.level.getSeed();
   }

   public RegistryAccess registryAccess() {
      return this.level.registryAccess();
   }

   public DimensionType dimensionType() {
      return this.store.dimensionType();
   }

   public LevelLightEngine getLightEngine() {
      return this.level.getLightEngine();
   }

   public int getSeaLevel() {
      return this.store.generator().getSeaLevel();
   }

   public MinecraftServer getServer() {
      return this.level.getServer();
   }

   public RandomSource getRandom() {
      return RandomSource.createThreadLocalInstance();
   }

   public LevelData getLevelData() {
      return this.level.getLevelData();
   }

   public EnvironmentAttributeSystem environmentAttributes() {
      return this.level.environmentAttributes();
   }

   public ChunkSource getChunkSource() {
      return this.level.getChunkSource();
   }

   public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
      return new DifficultyInstance(this.level.getDifficulty(), this.level.getGameTime(), 0L, 0.0F);
   }

   public long nextSubTickCount() {
      return 0L;
   }

   public LevelTickAccess<Block> getBlockTicks() {
      return this.blockTicks;
   }

   public LevelTickAccess<Fluid> getFluidTicks() {
      return this.fluidTicks;
   }

   public WorldBorder getWorldBorder() {
      return this.level.getWorldBorder();
   }

   public boolean isClientSide() {
      return false;
   }

   public FeatureFlagSet enabledFeatures() {
      return this.level.enabledFeatures();
   }

   public ChunkAccess getChunk(int x, int z) {
      return this.resolveChunk(x, z);
   }

   @Nullable
   public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean loadOrGenerate) {
      return this.resolveChunk(x, z);
   }

   public boolean hasChunk(int x, int z) {
      return true;
   }

   public BlockState getBlockState(BlockPos pos) {
      return this.chunkAt(pos).getBlockState(pos);
   }

   public FluidState getFluidState(BlockPos pos) {
      return this.chunkAt(pos).getFluidState(pos);
   }

   @Nullable
   public BlockEntity getBlockEntity(BlockPos pos) {
      return this.chunkAt(pos).getBlockEntity(pos);
   }

   public int getSkyDarken() {
      return 0;
   }

   public boolean setBlock(BlockPos pos, BlockState state, int updateFlags, int updateLimit) {
      ChunkAccess chunk = this.chunkAt(pos);
      BlockState oldState = chunk.setBlockState(pos, state, updateFlags);
      if (state.hasBlockEntity()) {
         BlockEntity be = chunk.getBlockEntity(pos);
         if (be == null) {
            be = ((EntityBlock)state.getBlock()).newBlockEntity(pos, state);
            if (be != null) {
               chunk.setBlockEntity(be);
            }
         }
      } else if (oldState != null && oldState.hasBlockEntity()) {
         chunk.removeBlockEntity(pos);
      }

      return oldState != null;
   }

   public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
      return this.setBlock(pos, Blocks.AIR.defaultBlockState(), 3, 512);
   }

   public boolean destroyBlock(BlockPos pos, boolean dropResources, @Nullable Entity breaker, int updateLimit) {
      BlockState state = this.getBlockState(pos);
      return !state.isAir() && this.setBlock(pos, Blocks.AIR.defaultBlockState(), 3, updateLimit);
   }

   public boolean addFreshEntity(Entity entity) {
      if (OneWorld.DEBUG_ENTITIES) {
         OneWorld.LOGGER
            .info(
               "[OneWorld][dbg] region.addFreshEntity {} at ({}, {}, {}) offset={}",
               new Object[]{entity.getType(), entity.getX(), entity.getY(), entity.getZ(), this.store.yOffset()}
            );
      }

      this.getChunk(SectionPos.blockToSectionCoord(entity.getBlockX()), SectionPos.blockToSectionCoord(entity.getBlockZ())).addEntity(entity);
      return true;
   }

   public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
      RandomState rs = this.store.randomState();
      return this.store.biomeSource().getNoiseBiome(quartX, quartY, quartZ, rs.sampler());
   }

   public BiomeManager getBiomeManager() {
      return new BiomeManager(this, BiomeManager.obfuscateSeed(this.getSeed()))
         .withDifferentSource((quartX, quartY, quartZ) -> this.store.biomeSource().getNoiseBiome(quartX, quartY, quartZ, this.store.randomState().sampler()));
   }

   public int getHeight(Types type, int x, int z) {
      ChunkAccess chunk = this.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
      return chunk.getHeight(type, x & 15, z & 15) + 1;
   }

   public List<? extends Player> players() {
      return List.of();
   }

   public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB box, Predicate<? super T> filter) {
      return List.of();
   }

   public List<Entity> getEntities(@Nullable Entity except, AABB box, Predicate<? super Entity> filter) {
      return List.of();
   }

   public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> state) {
      return state.test(this.getBlockState(pos));
   }

   public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
      return predicate.test(this.getFluidState(pos));
   }

   public void playSound(@Nullable Entity except, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
   }

   public void addParticle(ParticleOptions particle, double x, double y, double z, double xd, double yd, double zd) {
   }

   public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
   }

   public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, Context context) {
   }
}
