package tamermod.version.inventory;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Slot layout:
 *   0  = HEAD armour
 *   1  = CHEST armour
 *   2  = LEGS armour
 *   3  = FEET armour
 *   4  = MAIN_HAND
 *   5  = OFF_HAND
 *   6-32 = storage (27 slots, 3x9)
 *
 * Persistence: stored in a world-level PersistentState ("tamer_pet_inventories"),
 * keyed by pet UUID.
 *
 * NOTE: In Yarn 1.21.1 the type wrapper class is PersistentState.Type (inner class),
 * NOT a top-level PersistentStateType.  PersistentStateManager.getOrCreate takes
 * PersistentState.Type<T> + String.
 */
public class PetInventory implements Inventory {

    public static final int SLOT_HEAD     = 0;
    public static final int SLOT_CHEST    = 1;
    public static final int SLOT_LEGS     = 2;
    public static final int SLOT_FEET     = 3;
    public static final int SLOT_MAIN     = 4;
    public static final int SLOT_OFF      = 5;
    public static final int STORAGE_START = 6;
    public static final int STORAGE_SIZE  = 27;
    public static final int TOTAL         = 33;  // 6 equip + 27 storage

    private final MobEntity   pet;    // null on client
    private final ServerWorld world;  // null on client
    private final List<ItemStack> items = new ArrayList<>(TOTAL);

    public PetInventory(MobEntity pet, ServerWorld world) {
        this.pet   = pet;
        this.world = world;
        for (int i = 0; i < TOTAL; i++) items.add(ItemStack.EMPTY);
        if (pet != null && world != null) load();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PersistentState that stores all pet inventories for one world dimension
    // ─────────────────────────────────────────────────────────────────────────

    public static class TamerStorage extends PersistentState {

        private static final String STORAGE_ID = "tamer_pet_inventories";

        // petId (UUID) -> NbtList of {Slot, item-nbt} compounds
        private final Map<UUID, NbtList> data = new HashMap<>();

        public TamerStorage() { /* required no-arg supplier */ }

        // ── PersistentState.Type ──────────────────────────────────────────────
        // In 1.21.1 Yarn the inner class is PersistentState.Type<T>; its
        // constructor is: Type(Supplier<T>, BiFunction<NbtCompound, WrapperLookup, T>, DataFixTypes)
        // Pass null for DataFixTypes — we have no datafixer version.

        private static final PersistentState.Type<TamerStorage> TYPE =
                new PersistentState.Type<>(
                        TamerStorage::new,
                        TamerStorage::fromNbt,
                        null   // no DataFixer needed
                );

        public static TamerStorage getOrCreate(ServerWorld world) {
            PersistentStateManager mgr = world.getPersistentStateManager();
            return mgr.getOrCreate(TYPE, STORAGE_ID);
        }

        // ── Serialisation ─────────────────────────────────────────────────────

        public static TamerStorage fromNbt(NbtCompound tag,
                                           net.minecraft.registry.RegistryWrapper.WrapperLookup registries) {
            TamerStorage s = new TamerStorage();
            NbtCompound all = tag.getCompound("Pets");
            for (String key : all.getKeys()) {
                try {
                    UUID id = UUID.fromString(key);
                    s.data.put(id, all.getList(key, NbtElement.COMPOUND_TYPE));
                } catch (IllegalArgumentException ignored) {}
            }
            return s;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound tag,
                                    net.minecraft.registry.RegistryWrapper.WrapperLookup registries) {
            NbtCompound all = new NbtCompound();
            data.forEach((uuid, list) -> all.put(uuid.toString(), list));
            tag.put("Pets", all);
            return tag;
        }

        // ── Data accessors ────────────────────────────────────────────────────

        public NbtList getSlots(UUID petId) {
            return data.getOrDefault(petId, new NbtList());
        }

        /** Returns true if this pet has ever had its inventory saved by the mod. */
        public boolean hasPet(UUID petId) {
            return data.containsKey(petId);
        }

        public void putSlots(UUID petId, NbtList list) {
            data.put(petId, list);
            markDirty();
        }

        public void removePet(UUID petId) {
            data.remove(petId);
            markDirty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load / Save
    // ─────────────────────────────────────────────────────────────────────────

    private void load() {
        TamerStorage storage = TamerStorage.getOrCreate(world);

        if (storage.hasPet(pet.getUuid())) {
            // ── Pet has been opened before: restore from mod storage ──────────
            NbtList list = storage.getSlots(pet.getUuid());
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i);
                int slotIndex = entry.getByte("Slot") & 0xFF;
                if (slotIndex < TOTAL && entry.contains("Item")) {
                    // Item data was stored as a sub-element under "Item"
                    items.set(slotIndex,
                            ItemStack.fromNbt(world.getRegistryManager(), entry.get("Item"))
                                    .orElse(ItemStack.EMPTY));
                }
            }
        } else {
            // ── First time opening: seed equipment slots from the live entity ─
            // This ensures pre-existing armour/held items show up in the GUI
            // and are not lost when the inventory is saved for the first time.
            items.set(SLOT_HEAD,  pet.getEquippedStack(EquipmentSlot.HEAD).copy());
            items.set(SLOT_CHEST, pet.getEquippedStack(EquipmentSlot.CHEST).copy());
            items.set(SLOT_LEGS,  pet.getEquippedStack(EquipmentSlot.LEGS).copy());
            items.set(SLOT_FEET,  pet.getEquippedStack(EquipmentSlot.FEET).copy());
            items.set(SLOT_MAIN,  pet.getMainHandStack().copy());
            items.set(SLOT_OFF,   pet.getOffHandStack().copy());
            // Storage slots start empty — the mob has no "bag inventory".
            // Save immediately so the pet is registered in TamerStorage,
            // preventing a repeated first-open seed on the next open.
            save();
        }
    }

    private void save() {
        if (pet == null || world == null) return;

        NbtList list = new NbtList();
        for (int i = 0; i < TOTAL; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;
            // encode() RETURNS the serialized NbtElement — it does NOT mutate
            // the tag passed in.  Store it as a sub-element inside a wrapper
            // compound that also carries the slot index.
            net.minecraft.nbt.NbtElement encoded =
                    stack.encode(world.getRegistryManager(), new NbtCompound());
            NbtCompound entry = new NbtCompound();
            entry.putByte("Slot", (byte) i);
            entry.put("Item", encoded);
            list.add(entry);
        }

        TamerStorage.getOrCreate(world).putSlots(pet.getUuid(), list);
        applyEquipment();
    }

    /** Push armour/hand slots to the live entity so it renders correctly. */
    public void applyEquipment() {
        if (pet == null) return;
        pet.equipStack(EquipmentSlot.HEAD,  items.get(SLOT_HEAD).copy());
        pet.equipStack(EquipmentSlot.CHEST, items.get(SLOT_CHEST).copy());
        pet.equipStack(EquipmentSlot.LEGS,  items.get(SLOT_LEGS).copy());
        pet.equipStack(EquipmentSlot.FEET,  items.get(SLOT_FEET).copy());
        pet.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, items.get(SLOT_MAIN).copy());
        pet.setStackInHand(net.minecraft.util.Hand.OFF_HAND,  items.get(SLOT_OFF).copy());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inventory interface
    // ─────────────────────────────────────────────────────────────────────────

    @Override public int     size()    { return TOTAL; }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }

    @Override
    public ItemStack getStack(int slot) {
        return (slot >= 0 && slot < TOTAL) ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        if (slot < 0 || slot >= TOTAL) return ItemStack.EMPTY;
        ItemStack stack = items.get(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = stack.split(amount);
        if (stack.isEmpty()) items.set(slot, ItemStack.EMPTY);
        markDirty();
        return removed;
    }

    @Override
    public ItemStack removeStack(int slot) {
        if (slot < 0 || slot >= TOTAL) return ItemStack.EMPTY;
        ItemStack old = items.set(slot, ItemStack.EMPTY);
        markDirty();
        return old;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (slot < 0 || slot >= TOTAL) return;
        items.set(slot, stack);
        markDirty();
    }

    @Override public void markDirty() { save(); }

    @Override
    public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity player) {
        if (pet == null) return true;
        return pet.isAlive() && pet.squaredDistanceTo(player) <= 64.0;
    }

    @Override
    public void clear() {
        for (int i = 0; i < TOTAL; i++) items.set(i, ItemStack.EMPTY);
        markDirty();
    }
}