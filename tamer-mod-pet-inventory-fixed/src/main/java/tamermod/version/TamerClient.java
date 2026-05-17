package tamermod.version;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import tamermod.version.screen.PetScreen;
import tamermod.version.screen.PetScreenHandler;

@Environment(EnvType.CLIENT)
public class TamerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HandledScreens.register(PetScreenHandler.TYPE, PetScreen::new);
    }
}
