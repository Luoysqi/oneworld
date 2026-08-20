package com.oneworld.client.gui;

import com.oneworld.OneWorldConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class OneWorldConfigScreen extends Screen {
   private static final int BUTTON_HEIGHT = 20;
   private static final int BUTTON_PADDING = 16;
   private static final int MIN_BUTTON_WIDTH = 160;
   private final Screen parent;
   private StringWidget titleWidget;
   private Button restrictionsButton;
   private Button endNightSkyButton;
   private Button vanillaNetherLightButton;
   private Button doneButton;

   public OneWorldConfigScreen(Screen parent) {
      super(Component.translatable("oneworld.config.title"));
      this.parent = parent;
   }

   protected void init() {
      this.titleWidget = (StringWidget)this.addRenderableWidget(new StringWidget(this.getTitle(), this.font));
      this.restrictionsButton = (Button)this.addRenderableWidget(Button.builder(restrictionsLabel(), button -> {
         OneWorldConfig.set(!OneWorldConfig.restrictionsEnabled(), OneWorldConfig.endNightSky(), OneWorldConfig.vanillaNetherLight());
         button.setMessage(restrictionsLabel());
      }).bounds(0, 0, 160, 20).build());
      this.endNightSkyButton = (Button)this.addRenderableWidget(Button.builder(endNightSkyLabel(), button -> {
         OneWorldConfig.set(OneWorldConfig.restrictionsEnabled(), !OneWorldConfig.endNightSky(), OneWorldConfig.vanillaNetherLight());
         button.setMessage(endNightSkyLabel());
      }).bounds(0, 0, 160, 20).build());
      this.vanillaNetherLightButton = (Button)this.addRenderableWidget(Button.builder(vanillaNetherLightLabel(), button -> {
         OneWorldConfig.set(OneWorldConfig.restrictionsEnabled(), OneWorldConfig.endNightSky(), !OneWorldConfig.vanillaNetherLight());
         button.setMessage(vanillaNetherLightLabel());
      }).bounds(0, 0, 160, 20).build());
      this.doneButton = (Button)this.addRenderableWidget(
         Button.builder(Component.translatable("gui.done"), button -> this.onClose()).bounds(0, 0, 160, 20).build()
      );
      this.repositionElements();
   }

   protected void repositionElements() {
      int centerX = this.width / 2;
      int y = this.height / 4 + 16;
      if (this.titleWidget != null) {
         this.titleWidget.setPosition(centerX - this.titleWidget.getWidth() / 2, y);
      }

      y += 32;
      int buttonWidth = 160;
      buttonWidth = Math.max(buttonWidth, this.toggleWidth("oneworld.config.restrictions"));
      buttonWidth = Math.max(buttonWidth, this.toggleWidth("oneworld.config.endNightSky"));
      buttonWidth = Math.max(buttonWidth, this.toggleWidth("oneworld.config.vanillaNetherLight"));
      buttonWidth = Math.min(buttonWidth, Math.max(160, this.width - 40));
      int x = centerX - buttonWidth / 2;
      if (this.restrictionsButton != null) {
         this.restrictionsButton.setX(x);
         this.restrictionsButton.setWidth(buttonWidth);
         this.restrictionsButton.setY(y);
      }

      if (this.endNightSkyButton != null) {
         this.endNightSkyButton.setX(x);
         this.endNightSkyButton.setWidth(buttonWidth);
         this.endNightSkyButton.setY(y + 24);
      }

      if (this.vanillaNetherLightButton != null) {
         this.vanillaNetherLightButton.setX(x);
         this.vanillaNetherLightButton.setWidth(buttonWidth);
         this.vanillaNetherLightButton.setY(y + 48);
      }

      if (this.doneButton != null) {
         this.doneButton.setX(x);
         this.doneButton.setWidth(buttonWidth);
         this.doneButton.setY(y + 84);
      }
   }

   public void onClose() {
      if (this.minecraft != null) {
         this.minecraft.setScreenAndShow(this.parent);
      }
   }

   private int toggleWidth(String key) {
      int on = this.font.width(toggleLabel(key, true));
      int off = this.font.width(toggleLabel(key, false));
      return Math.max(on, off) + 16;
   }

   private static Component restrictionsLabel() {
      return toggleLabel("oneworld.config.restrictions", OneWorldConfig.restrictionsEnabled());
   }

   private static Component endNightSkyLabel() {
      return toggleLabel("oneworld.config.endNightSky", OneWorldConfig.endNightSky());
   }

   private static Component vanillaNetherLightLabel() {
      return toggleLabel("oneworld.config.vanillaNetherLight", OneWorldConfig.vanillaNetherLight());
   }

   private static Component toggleLabel(String key, boolean value) {
      return Component.translatable(key).append(": ").append(Component.translatable(value ? "options.on" : "options.off"));
   }
}
