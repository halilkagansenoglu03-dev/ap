package com.emptyquit;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side mod: if you can't SEE any other entity (player, mob, animal,
 * item, ...) for 10 seconds in a row, you are disconnected from the server.
 *
 * An entity counts as "seen" when:
 *  - it is inside your view cone (in front of you), and
 *  - no solid block is in the way (glass and other see-through blocks do not block).
 *
 * Only runs on multiplayer servers, never in singleplayer.
 */
public class EmptyQuitClient implements ClientModInitializer {

	/** How long you may see nothing before quitting, in seconds. */
	private static final int SECONDS_ALONE = 10;

	/** How wide your view is, measured from the center of the screen outward. */
	private static final double VIEW_HALF_ANGLE_DEGREES = 60.0;

	/** Entities further away than this many blocks never count. */
	private static final double MAX_DISTANCE = 128.0;

	/** 20 ticks per second. */
	private static final int TICKS_ALONE = SECONDS_ALONE * 20;

	private static final double COS_HALF_ANGLE = Math.cos(Math.toRadians(VIEW_HALF_ANGLE_DEGREES));

	/** Consecutive ticks with no visible entity. */
	private int emptyTicks = 0;

	@Override
	public void onInitializeClient() {
		// Fresh timer every time you join a server.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> emptyTicks = 0);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> emptyTicks = 0);

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.getConnection() == null) {
			emptyTicks = 0;
			return;
		}

		// Singleplayer / LAN host: never kick yourself out of your own world.
		if (mc.hasSingleplayerServer() || mc.getCurrentServer() == null) {
			emptyTicks = 0;
			return;
		}

		// Is at least one other entity visible to you?
		boolean seesSomething = false;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity != mc.player && canSee(mc, entity)) {
				seesSomething = true;
				break;
			}
		}

		if (seesSomething) {
			emptyTicks = 0;
			return;
		}

		emptyTicks++;
		if (emptyTicks >= TICKS_ALONE) {
			emptyTicks = 0;
			mc.getConnection().getConnection().disconnect(
					Component.literal("Empty Quit: you saw no entities for " + SECONDS_ALONE + " seconds."));
		}
	}

	/** True if the entity is in front of you and not hidden behind a solid block. */
	private boolean canSee(Minecraft mc, Entity entity) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 center = entity.getBoundingBox().getCenter();
		Vec3 toEntity = center.subtract(eye);
		double distance = toEntity.length();

		if (distance > MAX_DISTANCE) {
			return false;
		}

		// Inside your field of view? (Things right next to you always count.)
		if (distance > 0.5) {
			Vec3 look = mc.player.getViewVector(1.0F);
			double dot = look.dot(toEntity.scale(1.0 / distance));
			if (dot < COS_HALF_ANGLE) {
				return false;
			}
		}

		// Nothing solid in the way? Check the middle of the entity and its eyes.
		return lineIsClear(mc, eye, center) || lineIsClear(mc, eye, entity.getEyePosition());
	}

	private boolean lineIsClear(Minecraft mc, Vec3 from, Vec3 to) {
		HitResult hit = mc.level.clip(new ClipContext(
				from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS;
	}
}
