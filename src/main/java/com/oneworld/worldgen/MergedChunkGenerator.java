package com.oneworld.worldgen;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.oneworld.OneWorld;
import com.oneworld.OneWorldConfig;
import com.oneworld.mixin.ChunkAccessHeightmaps;
import com.oneworld.mixin.ChunkGeneratorAccessor;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FeatureSorter.StepFeatureData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature.EndSpike;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters;

public final class MergedChunkGenerator extends ChunkGenerator {
   public static final MapCodec<MergedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            ChunkGenerator.CODEC.fieldOf("overworld").forGetter(g -> g.overworld),
            ChunkGenerator.CODEC.fieldOf("nether").forGetter(g -> g.nether),
            ChunkGenerator.CODEC.fieldOf("end").forGetter(g -> g.end)
         )
         .apply(instance, MergedChunkGenerator::new)
   );
   private final NoiseBasedChunkGenerator overworld;
   private final NoiseBasedChunkGenerator nether;
   private final NoiseBasedChunkGenerator end;
   private final TempZoneGenerator netherZone;
   private final TempZoneGenerator endZone;
   private final TempZoneGenerator underworldZone;
   private volatile boolean prepared;
   private volatile RandomState overworldRandomState;
   private ChunkGeneratorStructureState overworldState;
   private ChunkGeneratorStructureState unionState;
   private volatile Map<Integer, List<Structure>> structuresByStep;
   private static final LevelHeightAccessor OVERWORLD_HEIGHTS = new LevelHeightAccessor() {
      public int getMinY() {
         return -64;
      }

      public int getHeight() {
         return 384;
      }
   };
   private static final Types[] FINAL_HEIGHTMAP_TYPES = new Types[]{
      Types.MOTION_BLOCKING, Types.MOTION_BLOCKING_NO_LEAVES, Types.WORLD_SURFACE, Types.OCEAN_FLOOR
   };

   public MergedChunkGenerator(ChunkGenerator overworld, ChunkGenerator nether, ChunkGenerator end) {
      super(new ZoneBiomeSource(overworld.getBiomeSource(), nether.getBiomeSource(), end.getBiomeSource()));
      if (overworld instanceof NoiseBasedChunkGenerator ow && nether instanceof NoiseBasedChunkGenerator ne && end instanceof NoiseBasedChunkGenerator en) {
         this.overworld = ow;
         this.nether = ne;
         this.end = en;
         this.netherZone = new TempZoneGenerator(
            "nether", Level.NETHER, ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.withDefaultNamespace("the_nether")), ne, -1008
         );
         this.endZone = new TempZoneGenerator(
            "end", Level.END, ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.withDefaultNamespace("the_end")), en, 1000
         );
         this.underworldZone = new TempZoneGenerator(
            "underworld",
            Level.OVERWORLD,
            ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.withDefaultNamespace("overworld")),
            ow,
            0,
            OVERWORLD_HEIGHTS,
            1592642174L
         );
      } else {
         throw new IllegalArgumentException("oneworld:merged expects three minecraft:noise generators");
      }
   }

   protected MapCodec<? extends ChunkGenerator> codec() {
      return CODEC;
   }

   public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long seed) {
      this.unionState = ChunkGeneratorStructureState.createForNormal(randomState, seed, this.biomeSource, structureSets);
      return this.unionState;
   }

   private void prepare(ServerLevel level) {
      if (!this.prepared) {
         this.prepareLocked(level);
      }
   }

   private synchronized void prepareLocked(ServerLevel level) {
      if (!this.prepared) {
         long seed = level.getSeed();
         HolderGetter<NoiseParameters> noises = level.registryAccess().lookupOrThrow(Registries.NOISE);
         this.overworldRandomState = RandomState.create((NoiseGeneratorSettings)this.overworld.generatorSettings().value(), noises, seed);
         this.overworldState = ChunkGeneratorStructureState.createForNormal(
            this.overworldRandomState, seed, this.overworld.getBiomeSource(), level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
         );
         this.prepared = true;
         OneWorld.LOGGER.info("[OneWorld] merged generator ready (seed {})", seed);
      }
   }

   public void createStructures(
      RegistryAccess registryAccess,
      ChunkGeneratorStructureState state,
      StructureManager structureManager,
      ChunkAccess chunk,
      StructureTemplateManager templateManager,
      ResourceKey<Level> levelKey
   ) {
      if (levelKey != null && levelKey.equals(Level.OVERWORLD)) {
         this.prepare(OneWorld.overworldLevel());
         synchronized (this.overworldState) {
            this.overworld.createStructures(registryAccess, this.overworldState, structureManager, chunk, templateManager, Level.OVERWORLD);
         }
      } else {
         super.createStructures(registryAccess, state, structureManager, chunk, templateManager, levelKey);
      }
   }

   public void createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess chunk) {
      super.createReferences(level, structureManager, chunk);
   }

   public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(
      ServerLevel level, HolderSet<Structure> structures, BlockPos pos, int radius, boolean skipKnown
   ) {
      List<Holder<Structure>> vanilla = new ArrayList<>();
      List<Holder<Structure>> nether = new ArrayList<>();
      List<Holder<Structure>> end = new ArrayList<>();
      Set<Holder<Biome>> owBiomes = this.overworld.getBiomeSource().possibleBiomes();
      Set<Holder<Biome>> netherBiomes = this.nether.getBiomeSource().possibleBiomes();
      Set<Holder<Biome>> endBiomes = this.end.getBiomeSource().possibleBiomes();

      for (Holder<Structure> holder : structures) {
         HolderSet<Biome> biomes = ((Structure)holder.value()).biomes();
         if (biomes.stream().anyMatch(owBiomes::contains)) {
            vanilla.add(holder);
         } else if (biomes.stream().anyMatch(netherBiomes::contains)) {
            nether.add(holder);
         } else if (biomes.stream().anyMatch(endBiomes::contains)) {
            end.add(holder);
         } else {
            vanilla.add(holder);
         }
      }

      Pair<BlockPos, Holder<Structure>> best = null;
      if (!vanilla.isEmpty()) {
         best = super.findNearestMapStructure(level, HolderSet.direct(vanilla), pos, radius, skipKnown);
      }

      best = nearestOf(best, this.findNearestZoneStructure(this.netherZone, level, nether, pos, radius), pos);
      return nearestOf(best, this.findNearestZoneStructure(this.endZone, level, end, pos, radius), pos);
   }

   private static Pair<BlockPos, Holder<Structure>> nearestOf(
      Pair<BlockPos, Holder<Structure>> current, Pair<BlockPos, Holder<Structure>> candidate, BlockPos center
   ) {
      if (candidate == null) {
         return current;
      } else {
         return current != null && !(center.distSqr((Vec3i)candidate.getFirst()) < center.distSqr((Vec3i)current.getFirst())) ? current : candidate;
      }
   }

   private Pair<BlockPos, Holder<Structure>> findNearestZoneStructure(
      TempZoneGenerator zone, ServerLevel level, List<Holder<Structure>> holders, BlockPos pos, int radius
   ) {
      if (holders.isEmpty()) {
         return null;
      } else {
         zone.prepare(level);
         ChunkGeneratorStructureState state = zone.structureState();
         long seed = state.getLevelSeed();
         Map<RandomSpreadStructurePlacement, List<Holder<Structure>>> byPlacement = new HashMap<>();

         for (Holder<Structure> holder : holders) {
            for (StructurePlacement placement : state.getPlacementsForStructure(holder)) {
               if (placement instanceof RandomSpreadStructurePlacement spread) {
                  byPlacement.computeIfAbsent(spread, p -> new ArrayList<>()).add(holder);
               }
            }
         }

         if (byPlacement.isEmpty()) {
            return null;
         } else {
            NoiseBasedChunkGenerator generator = zone.generator();
            BiomeSource biomeSource = zone.biomeSource();
            RandomState randomState = zone.randomState();
            LevelHeightAccessor heights = TempZoneGenerator.zoneHeights();
            RegistryAccess registryAccess = level.registryAccess();
            StructureTemplateManager templates = level.getStructureManager();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            Set<Long> seen = new HashSet<>();
            Pair<BlockPos, Holder<Structure>> best = null;

            for (int ring = 0; ring <= radius; ring++) {
               boolean foundThisRing = false;

               for (Entry<RandomSpreadStructurePlacement, List<Holder<Structure>>> entry : byPlacement.entrySet()) {
                  RandomSpreadStructurePlacement placementx = entry.getKey();
                  int spacing = placementx.spacing();

                  for (int i = -ring; i <= ring; i++) {
                     for (int j = -ring; j <= ring; j++) {
                        ChunkPos candidate = placementx.getPotentialStructureChunk(seed, cx + spacing * i, cz + spacing * j);
                        if (seen.add(candidate.pack()) && placementx.applyAdditionalChunkRestrictions(candidate.x(), candidate.z(), seed)) {
                           for (Holder<Structure> holder : entry.getValue()) {
                              Structure structure = (Structure)holder.value();
                              GenerationContext context = new GenerationContext(
                                 registryAccess, generator, biomeSource, randomState, templates, seed, candidate, heights, b -> structure.biomes().contains(b)
                              );
                              if (structure.findValidGenerationPoint(context).isPresent()) {
                                 BlockPos locatePos = placementx.getLocatePos(candidate).offset(0, zone.yOffset(), 0);
                                 if (best == null || pos.distSqr(locatePos) < pos.distSqr((Vec3i)best.getFirst())) {
                                    best = Pair.of(locatePos, holder);
                                 }

                                 foundThisRing = true;
                              }
                           }
                        }
                     }
                  }
               }

               if (foundThisRing) {
                  return best;
               }
            }

            return best;
         }
      }
   }

   public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
      this.prepare(OneWorld.overworldLevel());
      return this.overworld.fillFromNoise(blender, this.overworldRandomState, structureManager, chunk);
   }

   public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess protoChunk) {
      ServerLevel level = OneWorld.overworldLevel();
      this.prepare(level);
      this.netherZone.prepare(level);
      Holder<Biome> plains = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
      BiomeResolver constant = (x, y, z, sampler) -> plains;
      ChunkPos pos = protoChunk.getPos();
      int quartMinX = QuartPos.fromBlock(pos.getMinBlockX());
      int quartMinZ = QuartPos.fromBlock(pos.getMinBlockZ());
      LevelHeightAccessor heights = protoChunk.getHeightAccessorForGeneration();
      int owMinSec = SectionPos.blockToSectionCoord(-64);
      int owMaxSec = SectionPos.blockToSectionCoord(319);
      int gapMinSec = SectionPos.blockToSectionCoord(-880);
      int gapMaxSec = SectionPos.blockToSectionCoord(-193);

      for (int sectionY = heights.getMinSectionY(); sectionY <= heights.getMaxSectionY(); sectionY++) {
         if (sectionY < owMinSec || sectionY > owMaxSec) {
            LevelChunkSection section = protoChunk.getSection(protoChunk.getSectionIndexFromSectionY(sectionY));
            if (sectionY >= gapMinSec && sectionY <= gapMaxSec) {
               section.fillBiomesFromNoise(
                  this.nether.getBiomeSource(), this.netherZone.randomState().sampler(), quartMinX, QuartPos.fromSection(sectionY), quartMinZ
               );
            } else {
               section.fillBiomesFromNoise(constant, null, quartMinX, QuartPos.fromSection(sectionY), quartMinZ);
            }
         }
      }

      for (int sectionYx = owMinSec; sectionYx <= owMaxSec; sectionYx++) {
         LevelChunkSection section = protoChunk.getSection(protoChunk.getSectionIndexFromSectionY(sectionYx));
         section.fillBiomesFromNoise(
            this.overworld.getBiomeSource(), this.overworldRandomState.sampler(), quartMinX, QuartPos.fromSection(sectionYx), quartMinZ
         );
      }

      return CompletableFuture.completedFuture(protoChunk);
   }

   public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
      this.prepare(region.getLevel());
      this.overworld.buildSurface(region, structureManager, this.overworldRandomState, chunk);
   }

   public void applyCarvers(
      WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk
   ) {
      this.prepare(region.getLevel());
      this.overworld.applyCarvers(region, seed, this.overworldRandomState, biomeManager, structureManager, chunk);
      if (chunk instanceof ProtoChunk proto) {
         ServerLevel level = region.getLevel();
         ChunkPos pos = chunk.getPos();
         if (Math.abs(pos.x()) <= 1875000 && Math.abs(pos.z()) <= 1875000) {
            this.netherZone.copyInto(level, proto, pos);
         }

         this.endZone.copyInto(level, proto, pos);
         this.buildSpikes(proto, pos);
         this.pierceSpikes(proto, pos);
         if (OneWorldConfig.restrictionsEnabled()) {
            this.fillHeatZone(proto, pos);
         } else {
            this.fillUnderworldNormal(proto, pos);
         }

         this.stripBedrock(proto);
         Map<Types, Heightmap> maps = ((ChunkAccessHeightmaps)proto).oneworld$heightmaps();
         maps.remove(Types.WORLD_SURFACE_WG);
         maps.remove(Types.OCEAN_FLOOR_WG);
         maps.remove(Types.MOTION_BLOCKING);
         maps.remove(Types.WORLD_SURFACE);
         maps.remove(Types.OCEAN_FLOOR);
         this.primeBandedHeightmaps(proto);
         ((ChunkAccessHeightmaps)proto).oneworld$setNoiseChunk(null);
      }
   }

   private void buildSpikes(ProtoChunk chunk, ChunkPos pos) {
      if (Math.abs(pos.x()) <= 6 && Math.abs(pos.z()) <= 6) {
         List<EndSpike> spikes = this.endZone.spikes();
         int minX = pos.getMinBlockX();
         int minZ = pos.getMinBlockZ();
         MutableBlockPos p = new MutableBlockPos();

         for (EndSpike spike : spikes) {
            int cx = spike.getCenterX();
            int cz = spike.getCenterZ();
            int r = spike.getRadius();
            int height = spike.getHeight();
            int islandSurface = 1065;

            for (int dx = -r; dx <= r; dx++) {
               for (int dz = -r; dz <= r; dz++) {
                  int x = cx + dx;
                  int z = cz + dz;
                  if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16) {
                     boolean inColumn = dx * dx + dz * dz <= r * r + 1;
                     int top = 1000 + height + 10;

                     for (int y = 1000; y <= top; y++) {
                        if (inColumn && y < 1000 + height) {
                           chunk.setBlockState(p.set(x, y, z), Blocks.OBSIDIAN.defaultBlockState(), 3);
                        } else if (y > islandSurface) {
                           chunk.setBlockState(p.set(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                        }
                     }
                  }
               }
            }

            if (spike.isGuarded()) {
               for (int dx = -2; dx <= 2; dx++) {
                  for (int dzx = -2; dzx <= 2; dzx++) {
                     int x = cx + dx;
                     int z = cz + dzx;
                     if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16) {
                        boolean xEdge = Math.abs(dx) == 2;
                        boolean zEdge = Math.abs(dzx) == 2;

                        for (int dy = 0; dy <= 3; dy++) {
                           boolean topLayer = dy == 3;
                           if (xEdge || zEdge || topLayer) {
                              boolean xSide = xEdge || topLayer;
                              boolean zSide = zEdge || topLayer;
                              BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                              bars = (BlockState)bars.setValue(IronBarsBlock.NORTH, xSide && dzx != -2);
                              bars = (BlockState)bars.setValue(IronBarsBlock.SOUTH, xSide && dzx != 2);
                              bars = (BlockState)bars.setValue(IronBarsBlock.WEST, zSide && dx != -2);
                              bars = (BlockState)bars.setValue(IronBarsBlock.EAST, zSide && dx != 2);
                              chunk.setBlockState(p.set(x, 1000 + height + dy, z), bars, 3);
                           }
                        }
                     }
                  }
               }
            }

            if (cx >= minX && cx < minX + 16 && cz >= minZ && cz < minZ + 16) {
               chunk.setBlockState(p.set(cx, 1000 + height, cz), Blocks.BEDROCK.defaultBlockState(), 3);
               BlockState fire = FireBlock.getState(chunk, p.set(cx, 1000 + height + 1, cz));
               chunk.setBlockState(p, fire, 3);
            }
         }
      }
   }

   private static void fillBandDirect(ProtoChunk chunk, ChunkPos pos, int y0, int y1, MergedChunkGenerator.BandBlockSource source) {
      int minX = pos.getMinBlockX();
      int minZ = pos.getMinBlockZ();
      int y = y0;

      while (y <= y1) {
         int secIdx = chunk.getSectionIndex(y);
         LevelChunkSection sec = chunk.getSections()[secIdx];
         int secBottom = chunk.getMinY() + secIdx * 16;
         int secTopLocal = Math.min(15, y1 - secBottom);

         for (int ly = y - secBottom; ly <= secTopLocal; ly++) {
            int wy = secBottom + ly;

            for (int x = 0; x < 16; x++) {
               for (int z = 0; z < 16; z++) {
                  if (sec.getBlockState(x, ly, z).isAir()) {
                     sec.setBlockState(x, ly, z, source.blockAt(minX + x, wy, minZ + z));
                  }
               }
            }
         }

         y = secBottom + secTopLocal + 1;
      }
   }

   private void primeBandedHeightmaps(ProtoChunk chunk) {
      this.primeBandedHeightmaps(chunk, FINAL_HEIGHTMAP_TYPES);
   }

   public void oneworld$primeFeatureHeightmaps(ChunkAccess chunk, Set<Types> types) {
      for (Types type : types) {
         if (!chunk.hasPrimedHeightmap(type)) {
            this.primeBandedHeightmaps(chunk, types.toArray(new Types[0]));
            return;
         }
      }
   }

   private void primeBandedHeightmaps(ChunkAccess chunk, Types[] types) {
      Predicate<BlockState>[] predicates = new Predicate[types.length];
      Heightmap[] maps = new Heightmap[types.length];

      for (int i = 0; i < types.length; i++) {
         predicates[i] = types[i].isOpaque();
         maps[i] = chunk.getOrCreateHeightmapUnprimed(types[i]);
      }

      boolean[] resolved = new boolean[maps.length];
      int minY = chunk.getMinY();
      int highestFilled = chunk.getHighestFilledSectionIndex();
      int top = (highestFilled == -1 ? minY : minY + highestFilled * 16) + 16;

      for (int x = 0; x < 16; x++) {
         for (int z = 0; z < 16; z++) {
            int remaining = maps.length;
            int y = top - 1;

            while (y >= minY && remaining > 0) {
               if (y > 319 && y < 1000) {
                  y = 319;
               }

               int secIdx = chunk.getSectionIndex(y);
               LevelChunkSection sec = chunk.getSections()[secIdx];
               int secBottom = minY + secIdx * 16;
               if (sec.hasOnlyAir()) {
                  y = secBottom - 1;
               } else {
                  for (int ly = Math.min(15, y - secBottom); ly >= 0 && remaining > 0; ly--) {
                     BlockState state = sec.getBlockState(x, ly, z);
                     if (!state.is(Blocks.AIR)) {
                        int wy = secBottom + ly;

                        for (int t = 0; t < maps.length && remaining > 0; t++) {
                           if (!resolved[t] && predicates[t].test(state)) {
                              maps[t].update(x, wy, z, state);
                              resolved[t] = true;
                              remaining--;
                           }
                        }
                     }
                  }

                  y = secBottom - 1;
               }
            }

            Arrays.fill(resolved, false);
         }
      }
   }

   private void fillHeatZone(ProtoChunk chunk, ChunkPos pos) {
      fillBandDirect(chunk, pos, -192, -65, MergedChunkGenerator::heatBlock);
   }

   private static BlockState heatBlock(int x, int y, int z) {
      if (y == -192) {
         return Blocks.OBSIDIAN.defaultBlockState();
      } else {
         int cellHash = (x >> 2) * 3129871 ^ (z >> 2) * 116129781 ^ (y >> 2) * 69069;
         cellHash = cellHash * cellHash * 42317861 + cellHash * 11;
         if ((cellHash >> 16 & 65535) % 100 < 18 && (x & 3) < 3 && (y & 3) < 3 && (z & 3) < 3) {
            return Blocks.LAVA.defaultBlockState();
         } else {
            int h = x * 3129871 ^ z * 116129781 ^ y * 69069;
            h = h * h * 42317861 + h * 11;
            return (h >> 16 & 65535) % 100 < 14 ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState();
         }
      }
   }

   private void fillUnderworldNormal(ProtoChunk chunk, ChunkPos pos) {
      ServerLevel level = OneWorld.overworldLevel();
      int srcMin = -64;
      int srcMax = -1;
      this.underworldZone.copyBandInto(level, chunk, pos, srcMin, srcMax, -192, false);
      this.underworldZone.copyBandInto(level, chunk, pos, srcMin, srcMax, -128, true);
      int minX = pos.getMinBlockX();
      int minZ = pos.getMinBlockZ();
      MutableBlockPos p = new MutableBlockPos();
      BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();

      for (int y = -192; y <= -65; y++) {
         for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
               p.set(minX + x, y, minZ + z);
               BlockState state = chunk.getBlockState(p);
               if (state.is(Blocks.BEDROCK)) {
                  chunk.setBlockState(p, deepslate, 3);
               }
            }
         }
      }

      for (int x = 0; x < 16; x++) {
         for (int zx = 0; zx < 16; zx++) {
            chunk.setBlockState(p.set(minX + x, -192, minZ + zx), Blocks.OBSIDIAN.defaultBlockState(), 3);
         }
      }
   }

   private void pierceSpikes(ProtoChunk chunk, ChunkPos pos) {
      if (Math.abs(pos.x()) <= 6 && Math.abs(pos.z()) <= 6) {
         List<EndSpike> spikes = this.endZone.spikes();
         int minX = pos.getMinBlockX();
         int minZ = pos.getMinBlockZ();
         MutableBlockPos p = new MutableBlockPos();

         for (EndSpike spike : spikes) {
            int cx = spike.getCenterX();
            int cz = spike.getCenterZ();
            int r = spike.getRadius();
            int columnTop = 1000 + spike.getHeight() - 1;

            for (int dx = -r; dx <= r; dx++) {
               for (int dz = -r; dz <= r; dz++) {
                  if (dx * dx + dz * dz <= r * r) {
                     int x = cx + dx;
                     int z = cz + dz;
                     if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16 && chunk.getBlockState(p.set(x, columnTop, z)).is(Blocks.OBSIDIAN)) {
                        int y = columnTop;

                        while (y > 1000 && chunk.getBlockState(p.set(x, y, z)).is(Blocks.OBSIDIAN)) {
                           y--;
                        }

                        BlockState surface = chunk.getBlockState(p.set(x, y, z));
                        if (!surface.isAir() && !surface.is(Blocks.OBSIDIAN)) {
                           int exposed = columnTop - y;
                           if (exposed >= 1) {
                              int bottom;
                              for (bottom = y; bottom > 1000; bottom--) {
                                 BlockState s = chunk.getBlockState(p.set(x, bottom - 1, z));
                                 if (s.isAir() || s.is(Blocks.OBSIDIAN)) {
                                    break;
                                 }
                              }

                              for (int i = 1; i <= exposed && bottom - i >= 1000; i++) {
                                 chunk.setBlockState(p.set(x, bottom - i, z), Blocks.OBSIDIAN.defaultBlockState(), 3);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public List<EndSpike> endSpikes() {
      return this.endZone.spikes();
   }

   public List<EndSpike> endSpikes(ServerLevel level) {
      this.endZone.prepare(level);
      return this.endZone.spikes();
   }

   public BlockPos findOuterIsland(ServerLevel level) {
      this.prepare(level);
      int quartY = QuartPos.fromBlock(1064);

      for (int radius = 1024; radius <= 6144; radius += 128) {
         for (int step = 0; step < 8; step++) {
            double angle = step * Math.PI / 4.0;
            int x = (int)Math.round(Math.cos(angle) * radius);
            int z = (int)Math.round(Math.sin(angle) * radius);
            Holder<Biome> biome = this.endZone
               .biomeSource()
               .getNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z), this.endZone.randomState().sampler());
            if (biome.is(Biomes.END_HIGHLANDS) || biome.is(Biomes.END_MIDLANDS)) {
               NoiseColumn column = this.end.getBaseColumn(x, z, TempZoneGenerator.zoneHeights(), this.endZone.randomState());

               for (int y = 127; y >= 0; y--) {
                  BlockState s = column.getBlock(y);
                  if (s != null && !s.isAir() && s.blocksMotion()) {
                     return new BlockPos(x, y + 1 + 1000, z);
                  }
               }
            }
         }
      }

      return null;
   }

   private void stripBedrock(ProtoChunk chunk) {
      this.replaceBedrockLayer(chunk, -64, -60, Blocks.DEEPSLATE);
      this.replaceBedrockLayer(chunk, -1008, -1004, Blocks.NETHERRACK);
      this.replaceBedrockLayer(chunk, -885, -881, Blocks.NETHERRACK);
      this.placeNetherFloorBedrock(chunk);
   }

   private void placeNetherFloorBedrock(ProtoChunk chunk) {
      BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
      int secIdx = chunk.getSectionIndex(-1008);
      LevelChunkSection sec = chunk.getSections()[secIdx];
      int ly = -1008 - (chunk.getMinY() + secIdx * 16);

      for (int x = 0; x < 16; x++) {
         for (int z = 0; z < 16; z++) {
            sec.setBlockState(x, ly, z, bedrock);
         }
      }
   }

   private void replaceBedrockLayer(ChunkAccess chunk, int y0, int y1, Block replacement) {
      BlockState state = replacement.defaultBlockState();
      int minY = chunk.getMinY();
      int y = y0;

      while (y <= y1) {
         int secIdx = chunk.getSectionIndex(y);
         LevelChunkSection sec = chunk.getSections()[secIdx];
         int secBottom = minY + secIdx * 16;
         int secTopLocal = Math.min(15, y1 - secBottom);

         for (int ly = y - secBottom; ly <= secTopLocal; ly++) {
            for (int x = 0; x < 16; x++) {
               for (int z = 0; z < 16; z++) {
                  if (sec.getBlockState(x, ly, z).is(Blocks.BEDROCK)) {
                     sec.setBlockState(x, ly, z, state);
                  }
               }
            }
         }

         y = secBottom + secTopLocal + 1;
      }
   }

   public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
      ChunkPos centerPos = chunk.getPos();
      int owMinSection = SectionPos.blockToSectionCoord(-64);
      SectionPos sectionPos = SectionPos.of(centerPos, owMinSection);
      BlockPos origin = sectionPos.origin();
      Map<Integer, List<Structure>> structuresByStep = this.structuresByStep;
      if (structuresByStep == null) {
         Registry<Structure> structuresRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
         Map<Integer, List<Structure>> built = new HashMap<>();
         structuresRegistry.stream().forEach(s -> built.computeIfAbsent(s.step().ordinal(), k -> new ArrayList<>()).add(s));
         structuresByStep = built;
         this.structuresByStep = built;
      }

      Supplier<List<StepFeatureData>> featuresPerStep = ((ChunkGeneratorAccessor)(Object)this.overworld).oneworld$getFeaturesPerStep();
      List<StepFeatureData> featureList = featuresPerStep.get();
      WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
      long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
      Set<Holder<Biome>> possibleBiomes = new HashSet<>();
      int minSec = chunk.getSectionIndex(-64);
      int maxSec = chunk.getSectionIndex(319);

      for (int cx = centerPos.x() - 1; cx <= centerPos.x() + 1; cx++) {
         for (int cz = centerPos.z() - 1; cz <= centerPos.z() + 1; cz++) {
            ChunkAccess inRange = level.getChunk(cx, cz);

            for (int s = minSec; s <= maxSec; s++) {
               inRange.getSection(s).getBiomes().getAll(possibleBiomes::add);
            }
         }
      }

      possibleBiomes.retainAll(this.overworld.getBiomeSource().possibleBiomes());
      BoundingBox writable = new BoundingBox(centerPos.getMinBlockX(), -64, centerPos.getMinBlockZ(), centerPos.getMaxBlockX(), 319, centerPos.getMaxBlockZ());

      try {
         BandHeights.activateOverworld();
         int generationSteps = Math.max(Decoration.values().length, featureList.size());

         for (int stepIndex = 0; stepIndex < generationSteps; stepIndex++) {
            int index = 0;
            if (structureManager.shouldGenerateStructures()) {
               for (Structure structure : structuresByStep.getOrDefault(stepIndex, Collections.emptyList())) {
                  random.setFeatureSeed(decorationSeed, index, stepIndex);

                  for (StructureStart start : structureManager.startsForStructure(sectionPos, structure)) {
                     BoundingBox bb = start.getBoundingBox();
                     if (bb.maxY() >= -64 && bb.minY() <= 319) {
                        start.placeInChunk(level, structureManager, this, random, writable, centerPos);
                     }
                  }

                  index++;
               }
            }

            if (stepIndex < featureList.size()) {
               IntSet possibleFeaturesThisStep = new IntArraySet();

               for (Holder<Biome> biome : possibleBiomes) {
                  List<HolderSet<PlacedFeature>> featuresInBiome = ((Biome)biome.value()).getGenerationSettings().features();
                  if (stepIndex < featuresInBiome.size()) {
                     HolderSet<PlacedFeature> stepFeatures = featuresInBiome.get(stepIndex);
                     StepFeatureData data = featureList.get(stepIndex);
                     stepFeatures.stream().<PlacedFeature>map(Holder::value).forEach(f -> possibleFeaturesThisStep.add(data.indexMapping().applyAsInt(f)));
                  }
               }

               int[] indexes = possibleFeaturesThisStep.toIntArray();
               Arrays.sort(indexes);
               StepFeatureData data = featureList.get(stepIndex);

               for (int featureIndex = 0; featureIndex < indexes.length; featureIndex++) {
                  int globalIndex = indexes[featureIndex];
                  PlacedFeature feature = (PlacedFeature)data.features().get(globalIndex);
                  random.setFeatureSeed(decorationSeed, globalIndex, stepIndex);
                  feature.placeWithBiomeCheck(level, this, random, origin);
               }
            }
         }
      } finally {
         BandHeights.deactivate();
      }
   }

   public void spawnOriginalMobs(WorldGenRegion region) {
      ChunkPos center = region.getCenter();
      spawnBand(region, center, -64, 319, this.getSeaLevel());
      spawnBand(region, center, -1008, -881, -944);
      spawnBand(region, center, 1000, 1127, 1063);
   }

   private static void spawnBand(WorldGenRegion region, ChunkPos center, int minY, int maxY, int sampleY) {
      Holder<Biome> biome = region.getBiome(center.getWorldPosition().atY(Math.min(Math.max(sampleY, minY), maxY - 1)));
      WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
      random.setDecorationSeed(region.getSeed(), center.getMinBlockX(), center.getMinBlockZ());

      try {
         BandHeights.activate(minY, maxY);
         NaturalSpawner.spawnMobsForChunkGeneration(region, biome, center, random);
      } finally {
         BandHeights.deactivate();
      }
   }

   public int getGenDepth() {
      return 2272;
   }

   public int getSeaLevel() {
      return this.overworld.getSeaLevel();
   }

   public int getMinY() {
      return -1008;
   }

   public int getBaseHeight(int x, int z, Types type, LevelHeightAccessor level, RandomState randomState) {
      return this.overworld.getBaseHeight(x, z, type, level, this.prepared ? this.overworldRandomState : randomState);
   }

   public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
      return this.overworld.getBaseColumn(x, z, level, this.prepared ? this.overworldRandomState : randomState);
   }

   public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
      this.overworld.addDebugScreenInfo(info, randomState, pos);
   }

   @FunctionalInterface
   private interface BandBlockSource {
      BlockState blockAt(int var1, int var2, int var3);
   }
}
