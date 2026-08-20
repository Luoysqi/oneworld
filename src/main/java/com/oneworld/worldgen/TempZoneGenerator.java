package com.oneworld.worldgen;

import com.oneworld.OneWorld;
import com.oneworld.mixin.ChunkAccessHeightmaps;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.Aquifer.FluidPicker;
import net.minecraft.world.level.levelgen.Aquifer.FluidStatus;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature.EndSpike;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters;

public final class TempZoneGenerator {
   private static final LevelHeightAccessor ZONE_HEIGHTS = new LevelHeightAccessor() {
      public int getMinY() {
         return 0;
      }

      public int getHeight() {
         return 128;
      }
   };
   private final String zoneName;
   private final ResourceKey<Level> levelKey;
   private final ResourceKey<DimensionType> dimensionTypeKey;
   private final NoiseBasedChunkGenerator generator;
   private final int yOffset;
   private final LevelHeightAccessor heights;
   private final long seedOffset;
   private final Map<ChunkPos, ProtoChunk> chunks = new ConcurrentHashMap<>();
   private final Map<ChunkPos, TempZoneGenerator.Stage> stages = new ConcurrentHashMap<>();
   private final Set<Long> startsDone = ConcurrentHashMap.newKeySet();
   private final Set<Long> referencesDone = ConcurrentHashMap.newKeySet();
   private final ReentrantLock[] decorLocks = new ReentrantLock[16];
   private static final Semaphore NOISE_JOIN_PERMITS = new Semaphore(Math.max(1, Util.maxAllowedExecutorThreads() - 1));
   private final Object structureLock;
   private volatile List<EndSpike> spikeList;
   private volatile boolean prepared;
   private ServerLevel level;
   private long seed;
   private RandomState randomState;
   private ChunkGeneratorStructureState structureState;
   private StructureTemplateManager templateManager;
   private volatile ChunkPos lastAccess;
   private final ConcurrentHashMap<Long, Holder<Biome>> carverBiomeCache;

   private int stripeIndex(int cx, int cz) {
      return (cx >> 2 & 3) * 4 + (cz >> 2 & 3);
   }

   private List<ReentrantLock> zoneLocks(ChunkPos center) {
      TreeSet<Integer> stripes = new TreeSet<>();

      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            stripes.add(this.stripeIndex(center.x() + dx, center.z() + dz));
         }
      }

      List<ReentrantLock> locks = new ArrayList<>(stripes.size());

      for (int stripe : stripes) {
         locks.add(this.decorLocks[stripe]);
      }

      return locks;
   }

   private static void joinNoiseFills(List<CompletableFuture<ChunkAccess>> fills) {
      boolean interrupted = false;

      while (true) {
         try {
            NOISE_JOIN_PERMITS.acquire();
            break;
         } catch (InterruptedException var8) {
            interrupted = true;
         }
      }

      if (interrupted) {
         Thread.currentThread().interrupt();
      }

      try {
         for (CompletableFuture<ChunkAccess> fill : fills) {
            fill.join();
         }
      } finally {
         NOISE_JOIN_PERMITS.release();
      }
   }

   TempZoneGenerator(String zoneName, ResourceKey<Level> levelKey, ResourceKey<DimensionType> dimensionTypeKey, NoiseBasedChunkGenerator generator, int yOffset) {
      this(zoneName, levelKey, dimensionTypeKey, generator, yOffset, ZONE_HEIGHTS, 0L);
   }

   TempZoneGenerator(
      String zoneName,
      ResourceKey<Level> levelKey,
      ResourceKey<DimensionType> dimensionTypeKey,
      NoiseBasedChunkGenerator generator,
      int yOffset,
      LevelHeightAccessor heights
   ) {
      this(zoneName, levelKey, dimensionTypeKey, generator, yOffset, heights, 0L);
   }

   TempZoneGenerator(
      String zoneName,
      ResourceKey<Level> levelKey,
      ResourceKey<DimensionType> dimensionTypeKey,
      NoiseBasedChunkGenerator generator,
      int yOffset,
      LevelHeightAccessor heights,
      long seedOffset
   ) {
      for (int i = 0; i < this.decorLocks.length; i++) {
         this.decorLocks[i] = new ReentrantLock();
      }

      this.structureLock = new Object();
      this.carverBiomeCache = new ConcurrentHashMap<>();
      this.zoneName = zoneName;
      this.levelKey = levelKey;
      this.dimensionTypeKey = dimensionTypeKey;
      this.generator = generator;
      this.yOffset = yOffset;
      this.heights = heights;
      this.seedOffset = seedOffset;
   }

   ResourceKey<Level> levelKey() {
      return this.levelKey;
   }

   static LevelHeightAccessor zoneHeights() {
      return ZONE_HEIGHTS;
   }

   NoiseBasedChunkGenerator generator() {
      return this.generator;
   }

   BiomeSource biomeSource() {
      return this.generator.getBiomeSource();
   }

   RandomState randomState() {
      return this.randomState;
   }

   ChunkGeneratorStructureState structureState() {
      return this.structureState;
   }

   LevelHeightAccessor heights() {
      return this.heights;
   }

   DimensionType dimensionType() {
      return (DimensionType)this.level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(this.dimensionTypeKey).value();
   }

   int yOffset() {
      return this.yOffset;
   }

   ProtoChunk chunk(int x, int z) {
      return this.chunk(new ChunkPos(x, z));
   }

   ProtoChunk chunkAt(BlockPos pos) {
      return this.chunk(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
   }

   private ProtoChunk chunk(ChunkPos pos) {
      return this.chunks.computeIfAbsent(pos, p -> {
         ProtoChunk c = new ProtoChunk(p, UpgradeData.EMPTY, this.heights, this.level.palettedContainerFactory(), null);
         this.stages.put(p, TempZoneGenerator.Stage.CREATED);
         return c;
      });
   }

   private void sweep() {
      for (ReentrantLock lock : this.decorLocks) {
         lock.lock();
      }

      try {
         ChunkPos anchor = this.lastAccess != null ? this.lastAccess : this.chunks.keySet().iterator().next();
         int cx = anchor.x();
         int cz = anchor.z();
         this.chunks.entrySet().removeIf(e -> {
            ChunkPos p = e.getKey();
            if (Math.abs(p.x() - cx) <= 24 && Math.abs(p.z() - cz) <= 24) {
               return false;
            } else {
               this.stages.remove(p);
               this.startsDone.remove(p.pack());
               this.referencesDone.remove(p.pack());
               return true;
            }
         });
         this.carverBiomeCache.keySet().removeIf(key -> Math.abs((int)(key >>> 32) - cx) > 24 || Math.abs((int)key.longValue() - cz) > 24);
      } finally {
         for (int i = this.decorLocks.length - 1; i >= 0; i--) {
            this.decorLocks[i].unlock();
         }
      }
   }

   List<EndSpike> spikes() {
      List<EndSpike> local = this.spikeList;
      if (local == null) {
         if (this.level == null) {
            throw new IllegalStateException(this.zoneName + " zone queried before prepare()");
         }

         local = EndSpikeFeature.getSpikesForLevel(this.level);
         this.spikeList = local;
      }

      return local;
   }

   public void copyInto(ServerLevel serverLevel, ProtoChunk merged, ChunkPos pos) {
      if (!this.prepared) {
         this.prepare(serverLevel);
      }

      this.lastAccess = pos;
      if (this.chunks.size() > 800) {
         this.sweep();
      }

      ProtoChunk center = this.ensureDecorated(pos);
      this.copyChunk(center, merged);
   }

   void prepare(ServerLevel serverLevel) {
      if (!this.prepared) {
         this.prepareLocked(serverLevel);
      }
   }

   private synchronized void prepareLocked(ServerLevel serverLevel) {
      if (!this.prepared) {
         this.level = serverLevel;
         this.seed = serverLevel.getSeed() + this.seedOffset;
         this.templateManager = serverLevel.getStructureManager();
         HolderLookup<StructureSet> structureSets = serverLevel.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
         HolderGetter<NoiseParameters> noises = serverLevel.registryAccess().lookupOrThrow(Registries.NOISE);
         this.randomState = RandomState.create((NoiseGeneratorSettings)this.generator.generatorSettings().value(), noises, this.seed);
         this.structureState = ChunkGeneratorStructureState.createForNormal(this.randomState, this.seed, this.generator.getBiomeSource(), structureSets);
         this.prepared = true;
         OneWorld.LOGGER.info("[OneWorld] {} zone pipeline ready (seed {})", this.zoneName, this.seed);
      }
   }

   private ProtoChunk ensureDecorated(ChunkPos center) {
      List<ReentrantLock> locks = this.zoneLocks(center);

      for (ReentrantLock lock : locks) {
         lock.lock();
      }

      try {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               ChunkPos p = new ChunkPos(center.x() + dx, center.z() + dz);
               this.ensureStarts(p);
               this.ensureReferences(p);
            }
         }

         List<ChunkPos> pendingNoise = new ArrayList<>(9);
         List<CompletableFuture<ChunkAccess>> fills = new ArrayList<>(9);

         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               ChunkPos p = new ChunkPos(center.x() + dx, center.z() + dz);
               ProtoChunk chunk = this.chunk(p);
               if (this.stages.get(p) == TempZoneGenerator.Stage.CREATED) {
                  TempGenRegion region = new TempGenRegion(this.level, this, p);
                  fills.add(this.generator.fillFromNoise(Blender.empty(), this.randomState, new TempZoneGenerator.TempStructureManager(region), chunk));
                  pendingNoise.add(p);
               }
            }
         }

         if (!fills.isEmpty()) {
            joinNoiseFills(fills);

            for (ChunkPos p : pendingNoise) {
               this.chunk(p).fillBiomesFromNoise(this.generator.getBiomeSource(), this.randomState.sampler());
               this.stages.put(p, TempZoneGenerator.Stage.NOISED);
            }
         }

         for (int dx = -1; dx <= 1; dx++) {
            for (int dzx = -1; dzx <= 1; dzx++) {
               ChunkPos p = new ChunkPos(center.x() + dx, center.z() + dzx);
               this.ensureSurface(p);
               this.ensureCarved(p);
            }
         }

         ProtoChunk chunk = this.chunk(center);
         if (this.stages.get(center).ordinal() < TempZoneGenerator.Stage.DECORATED.ordinal()) {
            TempGenRegion region = new TempGenRegion(this.level, this, center);
            this.generator.applyBiomeDecoration(region, chunk, new TempZoneGenerator.TempStructureManager(region));
            this.stages.put(center, TempZoneGenerator.Stage.DECORATED);
         }

         return chunk;
      } finally {
         for (int i = locks.size() - 1; i >= 0; i--) {
            locks.get(i).unlock();
         }
      }
   }

   private void ensureStarts(ChunkPos pos) {
      if (!this.startsDone.contains(pos.pack())) {
         ProtoChunk chunk = this.chunk(pos);
         synchronized (this.structureLock) {
            if (!this.startsDone.contains(pos.pack())) {
               TempGenRegion region = new TempGenRegion(this.level, this, pos);
               this.generator
                  .createStructures(
                     this.level.registryAccess(),
                     this.structureState,
                     new TempZoneGenerator.TempStructureManager(region),
                     chunk,
                     this.templateManager,
                     this.levelKey
                  );
               this.startsDone.add(pos.pack());
            }
         }
      }
   }

   private void ensureSurface(ChunkPos pos) {
      ProtoChunk chunk = this.chunk(pos);
      if (this.stages.get(pos) == TempZoneGenerator.Stage.NOISED) {
         TempGenRegion region = new TempGenRegion(this.level, this, pos);
         Set<Holder<Biome>> possibleBiomes = new HashSet<>();

         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               this.chunk(pos.x() + dx, pos.z() + dz).collectBiomesInPalette(possibleBiomes);
            }
         }

         NoiseGeneratorSettings settings = (NoiseGeneratorSettings)this.generator.generatorSettings().value();
         NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> this.createNoiseChunk(c, region));
         WorldGenerationContext context = new WorldGenerationContext(this.generator, this.heights);
         this.randomState
            .surfaceSystem()
            .buildSurface(
               this.randomState, region.getBiomeManager(), settings.useLegacyRandomSource(), context, chunk, noiseChunk, settings.surfaceRule(), possibleBiomes
            );
         this.stages.put(pos, TempZoneGenerator.Stage.SURFACED);
      }
   }

   private void ensureCarved(ChunkPos pos) {
      ProtoChunk chunk = this.chunk(pos);
      if (this.stages.get(pos) == TempZoneGenerator.Stage.SURFACED) {
         TempGenRegion region = new TempGenRegion(this.level, this, pos);
         BiomeManager biomeManager = region.getBiomeManager();
         WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
         NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> this.createNoiseChunk(c, region));
         Aquifer aquifer = noiseChunk.aquifer();
         NoiseGeneratorSettings settings = (NoiseGeneratorSettings)this.generator.generatorSettings().value();
         CarvingContext context = new CarvingContext(
            this.generator, this.level.registryAccess(), this.heights, noiseChunk, this.randomState, settings.surfaceRule()
         );
         CarvingMask mask = chunk.getOrCreateCarvingMask();

         for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
               ChunkPos sourcePos = new ChunkPos(pos.x() + dx, pos.z() + dz);
               Holder<Biome> sourceBiome = this.carverBiomeCache
                  .computeIfAbsent(
                     sourcePos.pack(),
                     key -> this.generator
                        .getBiomeSource()
                        .getNoiseBiome(
                           QuartPos.fromBlock(sourcePos.getMinBlockX()), 0, QuartPos.fromBlock(sourcePos.getMinBlockZ()), this.randomState.sampler()
                        )
                  );
               Iterable<Holder<ConfiguredWorldCarver<?>>> carvers = ((Biome)sourceBiome.value()).getGenerationSettings().getCarvers();
               int index = 0;

               for (Holder<ConfiguredWorldCarver<?>> carverHolder : carvers) {
                  ConfiguredWorldCarver<?> carver = (ConfiguredWorldCarver<?>)carverHolder.value();
                  random.setLargeFeatureSeed(this.seed + index, sourcePos.x(), sourcePos.z());
                  if (carver.isStartChunk(random)) {
                     carver.carve(context, chunk, biomeManager::getBiome, random, aquifer, sourcePos, mask);
                  }

                  index++;
               }
            }
         }

         this.stages.put(pos, TempZoneGenerator.Stage.CARVED);
         ((ChunkAccessHeightmaps)chunk).oneworld$setNoiseChunk(null);
      }
   }

   private NoiseChunk createNoiseChunk(ChunkAccess chunk, TempGenRegion region) {
      NoiseGeneratorSettings settings = (NoiseGeneratorSettings)this.generator.generatorSettings().value();
      return NoiseChunk.forChunk(
         chunk,
         this.randomState,
         Beardifier.forStructuresInChunk(new TempZoneGenerator.TempStructureManager(region), chunk.getPos()),
         settings,
         globalFluidPicker(settings),
         Blender.empty()
      );
   }

   private static FluidPicker globalFluidPicker(NoiseGeneratorSettings settings) {
      FluidStatus lavaStatus = new FluidStatus(-54, Blocks.LAVA.defaultBlockState());
      int seaLevel = settings.seaLevel();
      FluidStatus seaStatus = new FluidStatus(seaLevel, settings.defaultFluid());
      new FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
      return (x, y, z) -> y < Math.min(-54, seaLevel) ? lavaStatus : seaStatus;
   }

   private void ensureReferences(ChunkPos pos) {
      if (!this.referencesDone.contains(pos.pack())) {
         this.chunk(pos);
         synchronized (this.structureLock) {
            if (!this.referencesDone.contains(pos.pack())) {
               for (int dx = -8; dx <= 8; dx++) {
                  for (int dz = -8; dz <= 8; dz++) {
                     this.ensureStarts(new ChunkPos(pos.x() + dx, pos.z() + dz));
                  }
               }

               TempGenRegion region = new TempGenRegion(this.level, this, pos);
               this.generator.createReferences(region, new TempZoneGenerator.TempStructureManager(region), this.chunk(pos));
               this.referencesDone.add(pos.pack());
            }
         }
      }
   }

   private void copyChunk(ProtoChunk src, ProtoChunk dst) {
      boolean aligned = (this.yOffset - dst.getMinY() & 15) == 0;
      if (aligned) {
         for (int zoneSec = 0; zoneSec < 8; zoneSec++) {
            LevelChunkSection source = src.getSection(zoneSec);
            int realIdx = dst.getSectionIndex(this.yOffset + zoneSec * 16);
            LevelChunkSection replacement = new LevelChunkSection(source.getStates().copy(), source.getBiomes().copy());
            replacement.recalcBlockCounts();
            dst.getSections()[realIdx] = replacement;
         }
      } else {
         int quartMinX = QuartPos.fromBlock(dst.getPos().getMinBlockX());
         int quartMinZ = QuartPos.fromBlock(dst.getPos().getMinBlockZ());

         for (int zoneSec = 0; zoneSec < 8; zoneSec++) {
            LevelChunkSection source = src.getSection(zoneSec);
            if (!source.hasOnlyAir() || source.hasFluid()) {
               for (int y = 0; y < 16; y++) {
                  int dstY = this.yOffset + zoneSec * 16 + y;
                  int dstSecIdx = dst.getSectionIndex(dstY);
                  LevelChunkSection dstSec = dst.getSection(dstSecIdx);
                  int localY = dstY - (dst.getMinY() + dstSecIdx * 16);

                  for (int x = 0; x < 16; x++) {
                     for (int z = 0; z < 16; z++) {
                        dstSec.setBlockState(x, localY, z, source.getBlockState(x, y, z));
                     }
                  }
               }
            }
         }

         Holder<Biome> gapBiome = this.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
         int zoneQuartOffset = QuartPos.fromBlock(this.yOffset);
         BiomeResolver resolver = (xx, y, zx, sampler) -> {
            int zoneQuartY = y - zoneQuartOffset;
            return zoneQuartY >= 0 && zoneQuartY <= 31
               ? this.generator.getBiomeSource().getNoiseBiome(xx, zoneQuartY, zx, this.randomState.sampler())
               : gapBiome;
         };

         for (int secIdx = dst.getSectionIndex(this.yOffset); secIdx <= dst.getSectionIndex(this.yOffset + 127); secIdx++) {
            LevelChunkSection dstSec = dst.getSection(secIdx);
            int sectionWorldY = dst.getMinY() + secIdx * 16;
            dstSec.fillBiomesFromNoise(resolver, null, quartMinX, QuartPos.fromBlock(sectionWorldY), quartMinZ);
            dstSec.recalcBlockCounts();
         }
      }

      Provider registries = this.level.registryAccess();

      for (Entry<BlockPos, BlockEntity> entry : src.getBlockEntities().entrySet()) {
         BlockEntity srcBe = entry.getValue();
         if (srcBe != null && entry.getKey() != null) {
            BlockPos newPos = entry.getKey().offset(0, this.yOffset, 0);
            CompoundTag tag = entry.getValue().saveWithFullMetadata(registries);
            if (entry.getValue() instanceof TheEndGatewayBlockEntity) {
               int[] exit = (int[])tag.getIntArray("exit_portal").orElse(null);
               if (exit != null && exit.length == 3) {
                  exit[1] += this.yOffset;
                  tag.putIntArray("exit_portal", exit);
               }
            }

            tag.putInt("x", newPos.getX());
            tag.putInt("y", newPos.getY());
            tag.putInt("z", newPos.getZ());
            BlockEntity newBe = BlockEntity.loadStatic(newPos, dst.getBlockState(newPos), tag, registries);
            if (newBe != null) {
               dst.setBlockEntity(newBe);
            } else {
               OneWorld.LOGGER
                  .warn("[OneWorld] {} zone: failed to copy block entity {} at {}", new Object[]{this.zoneName, entry.getValue().getType(), newPos});
            }
         }
      }

      for (CompoundTag tagx : src.getEntities()) {
         ListTag pos = tagx.getListOrEmpty("Pos");
         if (pos.size() == 3) {
            if (OneWorld.DEBUG_ENTITIES) {
               OneWorld.LOGGER
                  .info(
                     "[OneWorld][dbg] copyChunk entity {} y {} -> {} (chunk {})",
                     new Object[]{tagx.getStringOr("id", "?"), pos.getDoubleOr(1, 0.0), pos.getDoubleOr(1, 0.0) + this.yOffset, dst.getPos()}
                  );
            }

            pos.set(1, DoubleTag.valueOf(pos.getDoubleOr(1, 0.0) + this.yOffset));
         } else if (OneWorld.DEBUG_ENTITIES) {
            OneWorld.LOGGER.warn("[OneWorld][dbg] copyChunk entity {} without Pos list (chunk {})", tagx.getStringOr("id", "?"), dst.getPos());
         }

         dst.addEntity(tagx);
      }

      StructurePieceSerializationContext ctx = StructurePieceSerializationContext.fromLevel(this.level);

      for (Entry<Structure, StructureStart> entryx : src.getAllStarts().entrySet()) {
         if (dst.getStartForStructure(entryx.getKey()) == null) {
            StructureStart translated = translateStart(entryx.getValue(), ctx, this.yOffset);
            if (translated != null) {
               dst.setStartForStructure(entryx.getKey(), translated);
            }
         }
      }

      for (Entry<Structure, LongSet> entryxx : src.getAllReferences().entrySet()) {
         LongSet existing = dst.getReferencesForStructure(entryxx.getKey());
         LongIterator var39 = entryxx.getValue().iterator();

         while (var39.hasNext()) {
            long ref = (Long)var39.next();
            if (!existing.contains(ref)) {
               dst.addReferenceForStructure(entryxx.getKey(), ref);
            }
         }
      }
   }

   public void copyBandInto(ServerLevel serverLevel, ProtoChunk dst, ChunkPos pos, int srcMinY, int srcMaxY, int destMinY, boolean flip) {
      if (!this.prepared) {
         this.prepare(serverLevel);
      }

      this.lastAccess = pos;
      if (this.chunks.size() > 800) {
         this.sweep();
      }

      ProtoChunk src = this.ensureDecorated(pos);
      int shift = destMinY - srcMinY;
      IntUnaryOperator dstOf = flip ? y -> destMinY + (srcMaxY - y) : y -> y + shift;

      for (int y = srcMinY; y <= srcMaxY; y++) {
         int dstY = dstOf.applyAsInt(y);
         int dstSecIdx = dst.getSectionIndex(dstY);
         LevelChunkSection dstSec = dst.getSections()[dstSecIdx];
         int dstBottom = dst.getMinY() + dstSecIdx * 16;
         int dly = dstY - dstBottom;
         int srcSecIdx = src.getSectionIndex(y);
         LevelChunkSection srcSec = src.getSections()[srcSecIdx];
         int srcBottom = src.getMinY() + srcSecIdx * 16;
         int sly = y - srcBottom;

         for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
               dstSec.setBlockState(x, dly, z, srcSec.getBlockState(x, sly, z));
            }
         }
      }

      Provider registries = serverLevel.registryAccess();

      for (Entry<BlockPos, BlockEntity> entry : src.getBlockEntities().entrySet()) {
         BlockPos bePos = entry.getKey();
         BlockEntity srcBe = entry.getValue();
         if (bePos != null && srcBe != null && bePos.getY() >= srcMinY && bePos.getY() <= srcMaxY) {
            BlockPos newPos = new BlockPos(bePos.getX(), dstOf.applyAsInt(bePos.getY()), bePos.getZ());
            CompoundTag tag = srcBe.saveWithFullMetadata(registries);
            tag.putInt("x", newPos.getX());
            tag.putInt("y", newPos.getY());
            tag.putInt("z", newPos.getZ());
            BlockEntity newBe = BlockEntity.loadStatic(newPos, dst.getBlockState(newPos), tag, registries);
            if (newBe != null) {
               dst.setBlockEntity(newBe);
            }
         }
      }

      int quartMinX = QuartPos.fromBlock(pos.getMinBlockX());
      int quartMinZ = QuartPos.fromBlock(pos.getMinBlockZ());
      BiomeResolver resolver = (xx, y, zx, sampler) -> {
         int srcBlockY = flip ? srcMaxY - ((y << 2) - destMinY) : (y << 2) - shift;
         return this.generator.getBiomeSource().getNoiseBiome(xx, QuartPos.fromBlock(srcBlockY), zx, this.randomState.sampler());
      };

      for (int secIdx = dst.getSectionIndex(destMinY); secIdx <= dst.getSectionIndex(destMinY + (srcMaxY - srcMinY)); secIdx++) {
         LevelChunkSection dstSec = dst.getSection(secIdx);
         int sectionWorldY = dst.getMinY() + secIdx * 16;
         dstSec.fillBiomesFromNoise(resolver, null, quartMinX, QuartPos.fromBlock(sectionWorldY), quartMinZ);
      }
   }

   static StructureStart translateStart(StructureStart start, StructurePieceSerializationContext ctx, int yOffset) {
      try {
         CompoundTag tag = start.createTag(ctx, start.getChunkPos());
         ListTag children = tag.getListOrEmpty("Children");

         for (int i = 0; i < children.size(); i++) {
            CompoundTag child = children.getCompoundOrEmpty(i);
            int[] bb = (int[])child.getIntArray("BB").orElse(null);
            if (bb != null && bb.length == 6) {
               bb[1] += yOffset;
               bb[4] += yOffset;
               child.putIntArray("BB", bb);
            }

            child.putInt("PosY", child.getIntOr("PosY", Integer.MIN_VALUE) + yOffset);
            child.putInt("TPY", child.getIntOr("TPY", Integer.MIN_VALUE) + yOffset);
            int[] tp = (int[])child.getIntArray("TP").orElse(null);
            if (tp != null && tp.length == 3) {
               tp[1] += yOffset;
               child.putIntArray("TP", tp);
            }

            int[] tc = (int[])child.getIntArray("TC").orElse(null);
            if (tc != null && tc.length == 3) {
               tc[1] += yOffset;
               child.putIntArray("TC", tc);
            }
         }

         return StructureStart.loadStaticStart(ctx, tag, start.getChunkPos().pack());
      } catch (Exception var10) {
         OneWorld.LOGGER.warn("[OneWorld] could not translate structure start {}", start.getStructure(), var10);
         return null;
      }
   }

   private static enum Stage {
      CREATED,
      NOISED,
      SURFACED,
      CARVED,
      DECORATED;
   }

   static final class TempStructureManager extends StructureManager {
      TempStructureManager(TempGenRegion region) {
         super(region, null, null);
      }

      public boolean shouldGenerateStructures() {
         return true;
      }

      public void addReference(StructureStart start) {
         start.addReference();
      }
   }
}
