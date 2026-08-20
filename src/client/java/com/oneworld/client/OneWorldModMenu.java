package com.oneworld.client;

import com.oneworld.client.gui.OneWorldConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class OneWorldModMenu implements ModMenuApi {
   public ConfigScreenFactory<?> getModConfigScreenFactory() {
      return OneWorldConfigScreen::new;
   }
}
