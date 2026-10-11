package dev.darcosse.common.cobblemonsafari.client.gate;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.SafariGateBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * The Safari Gate's sound on the client: a relaxed tune looping at each gate
 * the player is near, in place of the nether portal's hum, and the soft chime
 * that replaces the portal's whoosh when the player steps into a pane.
 *
 * <p>One loop per gate: a pane being animated starts a loop unless one is
 * already playing close by. A loop stops by itself when its gate is gone or
 * the player has walked away. CLIENT ONLY, main thread.
 */
public final class GateAmbience {

    /** Sound events declared in sounds.json by tools/worldgen/generate_worldgen.py (GATE_SOUNDS). */
    private static final ResourceLocation AMBIENT_ID = CobblemonSafari.id("block.safari_gate.ambient");
    private static final ResourceLocation CHIME_ID = CobblemonSafari.id("block.safari_gate.chime");
    private static final SoundEvent AMBIENT = SoundEvent.createVariableRangeEvent(AMBIENT_ID);
    private static final SoundEvent CHIME = SoundEvent.createVariableRangeEvent(CHIME_ID);

    /**
     * Two panes closer than this belong to the same gate (a gate is about 3
     * blocks wide, 5 high), so they share one loop. Too small and a gate
     * plays several overlapping loops; too large and two neighbouring gates
     * share one.
     */
    private static final double SAME_GATE_DISTANCE = 8.0D;

    /**
     * Distance beyond which a loop stops. Above the sound's own range (16
     * blocks, where it is already silent), so walking back and forth at the
     * edge does not restart the tune from the beginning every time.
     */
    private static final double STOP_DISTANCE = 32.0D;

    /** Loop volume: background music, under the game's own sounds. */
    private static final float AMBIENT_VOLUME = 0.6F;
    /** Chime volume and pitch range: a little variation, as vanilla gives its whoosh. */
    private static final float CHIME_VOLUME = 0.5F;
    private static final float CHIME_PITCH_MIN = 0.95F;
    private static final float CHIME_PITCH_SPREAD = 0.1F;

    private static final List<GateLoop> PLAYING = new ArrayList<>();

    private GateAmbience() {
    }

    /** Hook for {@code SafariGateBlock.animateTick}: starts the tune of this pane's gate if it is not playing. */
    public static void onPaneAnimated(Level level, BlockPos pos) {
        PLAYING.removeIf(GateLoop::isStopped);
        double sameGate = SAME_GATE_DISTANCE * SAME_GATE_DISTANCE;
        for (GateLoop loop : PLAYING) {
            if (loop.level == level && loop.origin.distSqr(pos) <= sameGate) {
                return;
            }
        }
        GateLoop loop = new GateLoop(level, pos);
        PLAYING.add(loop);
        Minecraft.getInstance().getSoundManager().play(loop);
    }

    /** The chime played instead of the nether portal's whoosh when the portal countdown starts. */
    public static SoundInstance chime() {
        float pitch = CHIME_PITCH_MIN + (float) Math.random() * CHIME_PITCH_SPREAD;
        return SimpleSoundInstance.forLocalAmbience(CHIME, pitch, CHIME_VOLUME);
    }

    /** Forgets the loops; on disconnect the sound engine stops them anyway. */
    public static void clear() {
        PLAYING.clear();
    }

    /** The looping tune of one gate, heard from the pane that started it. */
    private static final class GateLoop extends AbstractTickableSoundInstance {
        final Level level;
        final BlockPos origin;

        GateLoop(Level level, BlockPos origin) {
            super(AMBIENT, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.level = level;
            this.origin = origin.immutable();
            this.looping = true;
            this.delay = 0;
            this.volume = AMBIENT_VOLUME;
            this.x = origin.getX() + 0.5D;
            this.y = origin.getY() + 0.5D;
            this.z = origin.getZ() + 0.5D;
        }

        /** Stops when the gate is gone, the player left its level, or walked out of earshot. */
        @Override
        public void tick() {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.level() != level
                    || !(level.getBlockState(origin).getBlock() instanceof SafariGateBlock)
                    || player.distanceToSqr(x, y, z) > STOP_DISTANCE * STOP_DISTANCE) {
                stop();
            }
        }
    }
}
