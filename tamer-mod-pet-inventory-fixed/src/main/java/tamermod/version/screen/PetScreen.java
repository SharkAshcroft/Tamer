package tamermod.version.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class PetScreen extends HandledScreen<PetScreenHandler> {

    // Matches actual file location:
    // src/main/resources/assets/tamer/gui/pet_inventory.png
    // Identifier path for assets/tamer/gui/... is "gui/pet_inventory.png"
    private static final Identifier TEXTURE =
            Identifier.of("tamer", "gui/pet_inventory.png");

    private static final int GUI_W = 176;
    private static final int GUI_H = 254;

    // Inset model box — matches what was drawn in pet_inventory.png
    private static final int MODEL_X1 = 27;
    private static final int MODEL_Y1 = -5;
    private static final int MODEL_X2 = 83;
    private static final int MODEL_Y2 = 120;

    public PetScreen(PetScreenHandler handler, PlayerInventory inv, Text title) {
        super(handler, inv, title);
        backgroundWidth       = GUI_W;
        backgroundHeight      = GUI_H;
        titleY                = 6;
        playerInventoryTitleY = 156;
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mx, int my) {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        int gx = (width  - GUI_W) / 2;
        int gy = (height - GUI_H) / 2;

        ctx.drawTexture(TEXTURE, gx, gy, 0, 0, GUI_W, GUI_H);

        if (handler.getPet() instanceof LivingEntity living) {

            int x1 = gx + MODEL_X1;
            int y1 = gy + MODEL_Y1 - 30;

            int x2 = gx + MODEL_X2;
            int y2 = gy + MODEL_Y2 - 30;

            InventoryScreen.drawEntity(
                    ctx,
                    x1, y1,
                    x2, y2,
                    30,
                    1.0f,
                    mx,
                    my,
                    living
            );
        }
    }

    @Override
    protected void drawForeground(DrawContext ctx, int mx, int my) {
        String petName = handler.getPet() != null
                ? handler.getPet().getName().getString() + "'s Inventory"
                : "Pet Inventory";
        ctx.drawText(textRenderer, petName, titleX, titleY, 0x404040, false);
        ctx.drawText(textRenderer, "Pet Storage", 7, 90, 0x404040, false);
        ctx.drawText(textRenderer, playerInventoryTitle,
                playerInventoryTitleX, playerInventoryTitleY, 0x404040, false);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);
        super.render(ctx, mx, my, delta);
        drawMouseoverTooltip(ctx, mx, my);
    }
}