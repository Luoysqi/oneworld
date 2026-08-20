package com.oneworld.worldgen;

public final class BandHeights {
   private static final ThreadLocal<int[]> BAND = new ThreadLocal<>();
   private static volatile Thread clientRenderThread;
   private static volatile int[] clientBand;

   private BandHeights() {
   }

   public static void activate(int minY, int maxY) {
      BAND.set(new int[]{minY, maxY});
   }

   public static void activateOverworld() {
      activate(-64, 319);
   }

   public static void deactivate() {
      BAND.set(null);
   }

   public static boolean isActivated() {
      return BAND.get() != null;
   }

   public static void setClientRenderThread(Thread thread) {
      clientRenderThread = thread;
   }

   public static void setClientBand(int minY, int maxY) {
      clientBand = new int[]{minY, maxY};
   }

   public static int[] bandFor(double y) {
      if (y >= 1000.0) {
         return new int[]{1000, 1127};
      } else if (y >= -64.0) {
         return new int[]{-64, 319};
      } else if (y >= -192.0) {
         return new int[]{-192, -65};
      } else {
         return y > -881.0 ? new int[]{-880, -193} : new int[]{-1008, -881};
      }
   }

   public static boolean needsClamp(int vanillaTopBlockY) {
      return vanillaTopBlockY > bandMaxY();
   }

   public static int bandMinY() {
      int[] band = BAND.get();
      if (band != null) {
         return band[0];
      } else {
         int[] client = clientBand;
         return client != null && clientRenderThread == Thread.currentThread() ? client[0] : -64;
      }
   }

   public static int bandMaxY() {
      int[] band = BAND.get();
      if (band != null) {
         return band[1];
      } else {
         int[] client = clientBand;
         return client != null && clientRenderThread == Thread.currentThread() ? client[1] : 319;
      }
   }
}
