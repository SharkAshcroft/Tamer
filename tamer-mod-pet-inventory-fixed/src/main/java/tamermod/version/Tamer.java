package tamermod.version;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.Item;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketDecoder;
import net.minecraft.network.codec.ValueFirstEncoder;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tamermod.version.inventory.PetInventory;
import tamermod.version.screen.PetScreenHandler;

import java.lang.reflect.Field;
import java.util.*;

public class Tamer implements ModInitializer {

	public static final String MOD_ID = "tamer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final Item GOLD_WHEAT = Registry.register(
			Registries.ITEM,
			Identifier.of(MOD_ID, "gold_wheat"),
			new Item(new Item.Settings())
	);

	// Maps: petId -> ownerId
	public static final Map<UUID, UUID> OWNER = new HashMap<>();

	private static final Map<UUID, Integer> SIT_STATE           = new HashMap<>();
	private static final Map<UUID, Long>    ANGRY_UNTIL         = new HashMap<>();
	private static final Map<UUID, UUID>    FORCED_TARGET       = new HashMap<>();
	private static final Map<UUID, Long>    SIT_TOGGLE_COOLDOWN = new HashMap<>();
	private static final Set<UUID>          GOALS_CLEARED       = new HashSet<>();

	private static final long   SIT_COOLDOWN_TICKS = 10L;
	private static final long   AGGRO_DECAY        = 6000L;
	private static final double FOLLOW_START_SQ    = 25.0;
	private static final double TELEPORT_SQ        = 144.0;
	private static final Random RANDOM             = new Random();

	/**
	 * UUID PacketCodec for RegistryByteBuf.
	 * PacketCodecs has no UUID field in 1.21.1 Yarn — we build one manually
	 * using PacketByteBuf#readUuid / PacketByteBuf#writeUuid which exist on
	 * the base class that RegistryByteBuf extends.
	 */
	public static final PacketCodec<RegistryByteBuf, UUID> UUID_CODEC =
			PacketCodec.of(
					(ValueFirstEncoder<RegistryByteBuf, UUID>) (uuid, buf) -> buf.writeUuid(uuid),
					(PacketDecoder<RegistryByteBuf, UUID>) buf -> buf.readUuid()
			);

	// -------------------------------------------------------------------------
	// HELPERS
	// -------------------------------------------------------------------------

	public static boolean isSitting(UUID id) { return SIT_STATE.getOrDefault(id, 0) == 1; }
	public static boolean isTamed(UUID id)   { return OWNER.containsKey(id); }

	private static void toggleSit(MobEntity mob, PlayerEntity owner) {
		UUID id  = mob.getUuid();
		long now = mob.getWorld().getTime();
		if (now - SIT_TOGGLE_COOLDOWN.getOrDefault(id, -999L) < SIT_COOLDOWN_TICKS) return;
		SIT_TOGGLE_COOLDOWN.put(id, now);

		int next = (SIT_STATE.getOrDefault(id, 0) == 0) ? 1 : 0;
		SIT_STATE.put(id, next);

		if (next == 1) {
			mob.getNavigation().stop();
			mob.setTarget(null);
			FORCED_TARGET.remove(id);
			mob.setVelocity(0, mob.getVelocity().y, 0);
			owner.sendMessage(Text.literal("Pet is now sitting."), true);
		} else {
			owner.sendMessage(Text.literal("Pet is now following."), true);
		}
	}

	private static Field findField(Class<?> clazz, String name) {
		while (clazz != null) {
			try { return clazz.getDeclaredField(name); }
			catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
		}
		return null;
	}

	private static void clearHostileGoals(MobEntity mob) {
		UUID id = mob.getUuid();
		if (GOALS_CLEARED.contains(id)) return;
		try {
			Field tf = findField(mob.getClass(), "targetSelector");
			if (tf == null) {
				LOGGER.warn("[Tamer] targetSelector not found on {}", mob.getClass().getSimpleName());
				return;
			}
			tf.setAccessible(true);
			GoalSelector targets = (GoalSelector) tf.get(mob);
			Field gf = findField(GoalSelector.class, "goals");
			if (gf == null) {
				LOGGER.warn("[Tamer] goals field not found in GoalSelector");
				return;
			}
			gf.setAccessible(true);
			((Set<?>) gf.get(targets)).clear();
			mob.setTarget(null);
			GOALS_CLEARED.add(id);
		} catch (Exception ex) {
			LOGGER.warn("[Tamer] clearHostileGoals failed for {}: {}", mob.getName().getString(), ex.getMessage());
		}
	}

	private static void assignTarget(MobEntity pet, LivingEntity target) {
		UUID petId = pet.getUuid(), ownerId = OWNER.get(petId);
		if (target == null || target == pet) return;
		if (ownerId != null && target.getUuid().equals(ownerId)) return;
		if (isTamed(target.getUuid())) return;
		pet.setTarget(target);
		FORCED_TARGET.put(petId, target.getUuid());
		ANGRY_UNTIL.put(petId, pet.getWorld().getTime() + AGGRO_DECAY);
	}

	// -------------------------------------------------------------------------
	// MOD INIT
	// -------------------------------------------------------------------------

	@Override
	public void onInitialize() {
		LOGGER.info("Tamer loaded!");

		// ExtendedScreenHandlerType<PetScreenHandler, UUID> requires a
		// PacketCodec<RegistryByteBuf, UUID>.  PacketCodecs has no UUID field
		// in Yarn 1.21.1; we use our own codec built from readUuid/writeUuid.
		PetScreenHandler.TYPE = Registry.register(
				Registries.SCREEN_HANDLER,
				Identifier.of(MOD_ID, "pet_inventory"),
				new ExtendedScreenHandlerType<>(
						(syncId, playerInv, uuid) -> new PetScreenHandler(syncId, playerInv, uuid),
						UUID_CODEC
				)
		);

		registerInteraction();
		registerDamageGuard();
		registerAttackAssist();
		registerFollowAI();
	}

	// -------------------------------------------------------------------------
	// INTERACTION
	// -------------------------------------------------------------------------

	private void registerInteraction() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {

			if (hand != Hand.MAIN_HAND)                     return ActionResult.PASS;
			if (world.isClient())                           return ActionResult.PASS;
			if (!(world instanceof ServerWorld sw))         return ActionResult.PASS;
			if (!(entity instanceof MobEntity mob))         return ActionResult.PASS;
			if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;

			UUID petId   = mob.getUuid();
			UUID ownerId = OWNER.get(petId);

			boolean holdingGoldWheat = player.getStackInHand(hand).getItem().equals(GOLD_WHEAT);
			boolean sneaking         = player.isSneaking();

			// Shift + right-click owner (no gold wheat) → open pet inventory
			if (ownerId != null && ownerId.equals(player.getUuid()) && sneaking && !holdingGoldWheat) {
				final PetInventory petInv = new PetInventory(mob, sw);
				final MobEntity    petRef = mob;

				sp.openHandledScreen(new ExtendedScreenHandlerFactory<UUID>() {
					@Override
					public Text getDisplayName() {
						return petRef.getName().copy().append(Text.literal("'s Inventory"));
					}

					@Override
					public ScreenHandler createMenu(int syncId, PlayerInventory playerInv, PlayerEntity p) {
						return new PetScreenHandler(syncId, playerInv, petInv, petRef);
					}

					@Override
					public UUID getScreenOpeningData(ServerPlayerEntity player2) {
						return petRef.getUuid();
					}
				});
				return ActionResult.SUCCESS;
			}

			// Right-click with gold wheat → attempt tame
			if (ownerId == null && holdingGoldWheat) {
				player.swingHand(hand);
				sw.playSound(null, mob.getBlockPos(), SoundEvents.ENTITY_GENERIC_EAT,
						SoundCategory.PLAYERS, 0.6f, 1.0f);
				if (RANDOM.nextFloat() <= 0.15f) {
					OWNER.put(petId, player.getUuid());
					SIT_STATE.put(petId, 0);
					mob.setTarget(null);
					FORCED_TARGET.remove(petId);
					GOALS_CLEARED.remove(petId);
					clearHostileGoals(mob);
					player.sendMessage(Text.literal("Tamed " + mob.getName().getString() + "!"), false);
					sw.spawnParticles(ParticleTypes.HEART,
							mob.getX(), mob.getY() + 1, mob.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
				} else {
					sw.spawnParticles(ParticleTypes.SMOKE,
							mob.getX(), mob.getY() + 1, mob.getZ(), 8, 0.4, 0.3, 0.4, 0.02);
				}
				return ActionResult.SUCCESS;
			}

			// Right-click (no sneak, no gold wheat) → toggle sit
			if (ownerId != null && ownerId.equals(player.getUuid()) && !sneaking && !holdingGoldWheat) {
				toggleSit(mob, player);
				return ActionResult.SUCCESS;
			}

			return ActionResult.PASS;
		});
	}

	// -------------------------------------------------------------------------
	// DAMAGE GUARD
	// -------------------------------------------------------------------------

	private void registerDamageGuard() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {

			Entity direct   = source.getSource();
			Entity attacker = source.getAttacker();
			LivingEntity aggressor = null;
			if (attacker instanceof LivingEntity lv) aggressor = lv;
			if (direct instanceof PersistentProjectileEntity p && p.getOwner() instanceof LivingEntity s) aggressor = s;
			if (direct instanceof TridentEntity t && t.getOwner() instanceof LivingEntity s) aggressor = s;

			if (entity instanceof MobEntity pet && OWNER.containsKey(pet.getUuid())) {
				UUID petId = pet.getUuid(), ownerId = OWNER.get(petId);
				if (aggressor instanceof PlayerEntity p && p.getUuid().equals(ownerId)) return false;
				if (aggressor != null && !isSitting(petId)) assignTarget(pet, aggressor);
				return true;
			}

			if (entity instanceof PlayerEntity victim && entity.getWorld() instanceof ServerWorld sw) {
				UUID victimId = victim.getUuid();
				for (UUID petId : new ArrayList<>(OWNER.keySet())) {
					if (!victimId.equals(OWNER.get(petId))) continue;
					Entity pe = sw.getEntity(petId);
					if (!(pe instanceof MobEntity pet)) continue;
					if (!isSitting(petId) && aggressor != null) assignTarget(pet, aggressor);
				}
			}
			return true;
		});
	}

	// -------------------------------------------------------------------------
	// ATTACK ASSIST
	// -------------------------------------------------------------------------

	private void registerAttackAssist() {
		AttackEntityCallback.EVENT.register((player, world, hand, target, hit) -> {
			if (world.isClient())                         return ActionResult.PASS;
			if (!(world instanceof ServerWorld sw))       return ActionResult.PASS;
			if (!(target instanceof LivingEntity living)) return ActionResult.PASS;
			for (UUID petId : new ArrayList<>(OWNER.keySet())) {
				if (!player.getUuid().equals(OWNER.get(petId))) continue;
				Entity e = sw.getEntity(petId);
				if (e instanceof MobEntity pet && !isSitting(petId)) assignTarget(pet, living);
			}
			return ActionResult.PASS;
		});
	}

	// -------------------------------------------------------------------------
	// FOLLOW / AGGRO AI
	// -------------------------------------------------------------------------

	private void registerFollowAI() {
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			long now = world.getTime();
			for (UUID petId : new ArrayList<>(OWNER.keySet())) {
				Entity e = world.getEntity(petId);
				if (!(e instanceof MobEntity pet)) continue;
				if (!GOALS_CLEARED.contains(petId)) clearHostileGoals(pet);

				PlayerEntity owner = world.getPlayerByUuid(OWNER.get(petId));
				if (owner == null) continue;

				long angryUntil = ANGRY_UNTIL.getOrDefault(petId, -1L);
				if (angryUntil > 0 && now >= angryUntil) {
					pet.setTarget(null);
					FORCED_TARGET.remove(petId);
					ANGRY_UNTIL.remove(petId);
				}

				if (isSitting(petId)) {
					pet.getNavigation().stop();
					pet.setTarget(null);
					FORCED_TARGET.remove(petId);
					pet.setVelocity(0, pet.getVelocity().y, 0);
					continue;
				}

				UUID forcedId = FORCED_TARGET.get(petId);
				if (forcedId != null) {
					LivingEntity cur = pet.getTarget();
					if (cur == null || !cur.getUuid().equals(forcedId)) {
						Entity te = world.getEntity(forcedId);
						if (te instanceof LivingEntity lv && lv.isAlive()
								&& !lv.getUuid().equals(OWNER.get(petId))
								&& !isTamed(lv.getUuid()))
							pet.setTarget(lv);
						else FORCED_TARGET.remove(petId);
					}
				}

				LivingEntity cur = pet.getTarget();
				if (cur != null && (cur.getUuid().equals(OWNER.get(petId)) || isTamed(cur.getUuid()))) {
					pet.setTarget(null);
					FORCED_TARGET.remove(petId);
				}

				if (pet.getTarget() != null && pet.getTarget().isAlive()) continue;

				double distSq = pet.squaredDistanceTo(owner);
				if      (distSq > TELEPORT_SQ)     pet.requestTeleport(owner.getX(), owner.getY(), owner.getZ());
				else if (distSq > FOLLOW_START_SQ)  pet.getNavigation().startMovingTo(owner, 1.15);
				else                                pet.getNavigation().stop();
			}
		});
	}
}