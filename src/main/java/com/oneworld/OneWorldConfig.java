package com.oneworld;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.loader.api.FabricLoader;

public final class OneWorldConfig {
   private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("oneworld.txt");
   private static volatile boolean restrictions = true;
   private static volatile boolean endNightSky = true;
   private static volatile boolean vanillaNetherLight = true;
   private static volatile long lastMtime;
   private static final String END_NIGHT_APPEND = "\n\n# 末地天空样式 End Sky\n# true  = 开启（默认）：末地带渐变永夜，光影强制末地配置\n#         End band fades to eternal night; forces End shaders (default)\n# false = 关闭：末地带跟随主世界昼夜循环\n#         End band follows the overworld day/night cycle\nendNightSky=true\n";
   private static final String VANILLA_NETHER_APPEND = "\n\n# 原版下界亮度 Vanilla Nether Light\n# true  = 开启（默认）：下界带使用原版下界的暖色环境光，光影切换下界配置\n#         Nether band uses the vanilla nether's warm light + nether shaders (default)\n# false = 关闭：下界带保持偏暗的主世界式环境光\n#         Nether band keeps this mod's dark ambient light\nvanillaNetherLight=true\n";

   private OneWorldConfig() {
   }

   public static boolean restrictionsEnabled() {
      return restrictions;
   }

   public static boolean endNightSky() {
      return endNightSky;
   }

   public static boolean vanillaNetherLight() {
      return vanillaNetherLight;
   }

   public static synchronized void set(boolean newRestrictions, boolean newEndNightSky, boolean newVanillaNetherLight) {
      restrictions = newRestrictions;
      endNightSky = newEndNightSky;
      vanillaNetherLight = newVanillaNetherLight;
      writeFile(newRestrictions, newEndNightSky, newVanillaNetherLight);
   }

   public static void maybeReload() {
      try {
         long mtime = Files.getLastModifiedTime(CONFIG_FILE).toMillis();
         if (mtime != lastMtime) {
            load();
         }
      } catch (IOException var2) {
      }
   }

   public static void load() {
      synchronized (OneWorldConfig.class) {
         try {
            lastMtime = Files.getLastModifiedTime(CONFIG_FILE).toMillis();
         } catch (IOException var7) {
            lastMtime = 0L;
         }

         try {
            if (!Files.exists(CONFIG_FILE)) {
               writeFile(true, true, true);
               OneWorld.LOGGER.info("[OneWorld] default config written to {}", CONFIG_FILE);
               return;
            }

            boolean hasEndNightKey = false;
            boolean hasVanillaNetherKey = false;

            for (String raw : Files.readAllLines(CONFIG_FILE, StandardCharsets.UTF_8)) {
               String line = raw.trim();
               if (!line.isEmpty() && line.charAt(0) == '\ufeff') {
                  line = line.substring(1).trim();
               }

               if (line.startsWith("enableRestrictions=")) {
                  restrictions = parseBool(line.substring("enableRestrictions=".length()), restrictions);
               } else if (line.startsWith("endNightSky=")) {
                  endNightSky = parseBool(line.substring("endNightSky=".length()), endNightSky);
                  hasEndNightKey = true;
               } else if (line.startsWith("vanillaNetherLight=")) {
                  vanillaNetherLight = parseBool(line.substring("vanillaNetherLight=".length()), vanillaNetherLight);
                  hasVanillaNetherKey = true;
               }
            }

            StringBuilder append = new StringBuilder();
            if (!hasEndNightKey) {
               append.append(
                  "\n\n# 末地天空样式 End Sky\n# true  = 开启（默认）：末地带渐变永夜，光影强制末地配置\n#         End band fades to eternal night; forces End shaders (default)\n# false = 关闭：末地带跟随主世界昼夜循环\n#         End band follows the overworld day/night cycle\nendNightSky=true\n"
               );
            }

            if (!hasVanillaNetherKey) {
               append.append(
                  "\n\n# 原版下界亮度 Vanilla Nether Light\n# true  = 开启（默认）：下界带使用原版下界的暖色环境光，光影切换下界配置\n#         Nether band uses the vanilla nether's warm light + nether shaders (default)\n# false = 关闭：下界带保持偏暗的主世界式环境光\n#         Nether band keeps this mod's dark ambient light\nvanillaNetherLight=true\n"
               );
            }

            if (!append.isEmpty()) {
               Files.writeString(CONFIG_FILE, append.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
               lastMtime = Files.getLastModifiedTime(CONFIG_FILE).toMillis();
            }
         } catch (IOException var8) {
            OneWorld.LOGGER
               .warn(
                  "[OneWorld] could not read {}; config stays restrictions={} endNightSky={} vanillaNetherLight={}",
                  new Object[]{CONFIG_FILE, restrictions, endNightSky, vanillaNetherLight, var8}
               );
         }

         OneWorld.LOGGER
            .info(
               "[OneWorld] restrictions enabled = {}, end night sky = {}, vanilla nether light = {}",
               new Object[]{restrictions, endNightSky, vanillaNetherLight}
            );
      }
   }

   private static void writeFile(boolean restrictionsValue, boolean endNightSkyValue, boolean vanillaNetherLightValue) {
      try {
         Files.writeString(CONFIG_FILE, fileContent(restrictionsValue, endNightSkyValue, vanillaNetherLightValue), StandardCharsets.UTF_8);
         lastMtime = Files.getLastModifiedTime(CONFIG_FILE).toMillis();
      } catch (IOException var4) {
         OneWorld.LOGGER.warn("[OneWorld] could not write {}", CONFIG_FILE, var4);
      }
   }

   private static String fileContent(boolean restrictionsValue, boolean endNightSkyValue, boolean vanillaNetherLightValue) {
      return "# ============================================\n#  三界合一 OneWorld 配置 Config\n# ============================================\n# 修改后自动热生效（每秒检测一次）/ Changes apply automatically (polled once a second).\n# 也可在模组菜单 - 三界合一 - 配置中修改 / Or edit in Mod Menu > OneWorld > Config.\n# 世界生成部分仅对尚未生成的新区块生效 / Worldgen applies to new chunks only.\n\n# 限制系统 Restrictions\n# true  = 开启（默认）：幻翼防空系统 + 灼热层 / Sky phantoms + heat zone (default)\n# false = 关闭：无幻翼无热层，该区域生成普通深层石与矿物\n#         No phantoms or heat zone; normal deepslate and ores instead\nenableRestrictions=%s\n\n# 末地天空样式 End Sky\n# true  = 开启（默认）：末地带渐变永夜，光影强制末地配置\n#         End band fades to eternal night; forces End shaders (default)\n# false = 关闭：末地带跟随主世界昼夜循环\n#         End band follows the overworld day/night cycle\nendNightSky=%s\n\n# 原版下界亮度 Vanilla Nether Light\n# true  = 开启（默认）：下界带使用原版下界的暖色环境光，光影切换下界配置\n#         Nether band uses the vanilla nether's warm light + nether shaders (default)\n# false = 关闭：下界带保持偏暗的主世界式环境光\n#         Nether band keeps this mod's dark ambient light\nvanillaNetherLight=%s\n"
         .formatted(restrictionsValue, endNightSkyValue, vanillaNetherLightValue);
   }

   private static boolean parseBool(String value, boolean fallback) {
      value = value.trim();
      if (value.equalsIgnoreCase("true")) {
         return true;
      } else {
         return value.equalsIgnoreCase("false") ? false : fallback;
      }
   }
}
