package tamermod.version.screen;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import tamermod.version.inventory.PetInventory;

import java.util.UUID;

public class PetScreenHandler extends ScreenHandler {

    public static ScreenHandlerType<PetScreenHandler> TYPE;

    private final PetInventory petInventory;
    private final MobEntity    pet;
    private final UUID         petUuid;

    // Slot pixel positions — must match pet_inventory.png
    static final int ARMOUR_X     = 8;
    static final int ARMOUR_Y     = 19;
    static final int HAND_MAIN_X  = 85;
    static final int HAND_OFF_X   = 103;
    static final int HAND_Y       = 73;
    static final int STORAGE_X    = 8;
    static final int STORAGE_Y    = 101;
    static final int PLAYER_INV_X = 8;
    static final int PLAYER_INV_Y = 167;
    static final int HOTBAR_Y     = 225;

    // ── Server-side constructor ───────────────────────────────────────────────

    public PetScreenHandler(int syncId, PlayerInventory playerInv,
                            PetInventory petInv, MobEntity pet) {
        super(TYPE, syncId);
        this.petInventory = petInv;
        this.pet          = pet;
        this.petUuid      = pet != null ? pet.getUuid() : null;
        if (petInv != null) petInv.onOpen(playerInv.player);
        addEquipmentSlots();
        addStorageSlots();
        addPlayerSlots(playerInv);
    }

    // ── Client-side constructor ───────────────────────────────────────────────
    // Invoked by ExtendedScreenHandlerType after decoding the UUID from the
    // server packet using Tamer.UUID_CODEC.

    public PetScreenHandler(int syncId, PlayerInventory playerInv, UUID petUuid) {
        super(TYPE, syncId);
        this.petUuid = petUuid;

        // Look up the entity in the client world by UUID.
        // ClientWorld.getEntities() returns an Iterable — use a for-each loop
        // (no .stream() on Iterable in 1.21.1).
        MobEntity found = null;
        ClientWorld cw = MinecraftClient.getInstance().world;
        if (cw != null) {
            for (net.minecraft.entity.Entity en : cw.getEntities()) {
                if (en.getUuid().equals(petUuid) && en instanceof MobEntity mob) {
                    found = mob;
                    break;
                }
            }
        }

        this.pet          = found;
        this.petInventory = new PetInventory(null, null); // client-side placeholder
        addEquipmentSlots();
        addStorageSlots();
        addPlayerSlots(playerInv);
    }

    // ── Slot builders ─────────────────────────────────────────────────────────

    private void addEquipmentSlots() {
        addSlot(equipSlot(PetInventory.SLOT_HEAD,  EquipmentSlot.HEAD,  ARMOUR_X, ARMOUR_Y));
        addSlot(equipSlot(PetInventory.SLOT_CHEST, EquipmentSlot.CHEST, ARMOUR_X, ARMOUR_Y + 18));
        addSlot(equipSlot(PetInventory.SLOT_LEGS,  EquipmentSlot.LEGS,  ARMOUR_X, ARMOUR_Y + 36));
        addSlot(equipSlot(PetInventory.SLOT_FEET,  EquipmentSlot.FEET,  ARMOUR_X, ARMOUR_Y + 54));
        addSlot(new Slot(petInventory, PetInventory.SLOT_MAIN, HAND_MAIN_X, HAND_Y) {
            @Override public void setStack(ItemStack s) {
                super.setStack(s);
                if (pet != null) pet.setStackInHand(Hand.MAIN_HAND, s);
            }
        });
        addSlot(new Slot(petInventory, PetInventory.SLOT_OFF, HAND_OFF_X, HAND_Y) {
            @Override public void setStack(ItemStack s) {
                super.setStack(s);
                if (pet != null) pet.setStackInHand(Hand.OFF_HAND, s);
            }
        });
    }

    private Slot equipSlot(int index, EquipmentSlot type, int x, int y) {
        return new Slot(petInventory, index, x, y) {
            @Override public int getMaxItemCount() { return 1; }
            @Override public void setStack(ItemStack s) {
                super.setStack(s);
                if (pet != null) pet.equipStack(type, s);
            }
        };
    }

    private void addStorageSlots() {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(petInventory,
                        PetInventory.STORAGE_START + row * 9 + col,
                        STORAGE_X + col * 18, STORAGE_Y + row * 18));
    }

    private void addPlayerSlots(PlayerInventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9,
                        PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, PLAYER_INV_X + col * 18, HOTBAR_Y));
    }

    // ── ScreenHandler overrides ───────────────────────────────────────────────

    @Override
    public boolean canUse(PlayerEntity player) {
        return petInventory == null || petInventory.canPlayerUse(player);
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        if (petInventory != null) petInventory.onClose(player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasStack()) return ItemStack.EMPTY;
        ItemStack stack = slot.getStack();
        ItemStack copy  = stack.copy();
        int petTotal  = PetInventory.TOTAL;
        int playerEnd = slots.size();
        if (index < petTotal) {
            if (!insertItem(stack, petTotal, playerEnd, true)) return ItemStack.EMPTY;
        } else {
            if (!insertItem(stack, PetInventory.STORAGE_START, petTotal, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
        else slot.markDirty();
        if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
        slot.onTakeItem(player, stack);
        return copy;
    }

    public MobEntity getPet()     { return pet; }
    public UUID      getPetUuid() { return petUuid; }
}