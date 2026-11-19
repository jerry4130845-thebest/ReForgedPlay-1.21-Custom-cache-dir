package com.replaymod.replay.mixin;

import com.replaymod.recording.mixin.NetworkManagerAccessor;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySender;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Allows a duplicate objective CREATE only on the current local replay connection.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class Mixin_ReplaceDuplicateReplayObjective {
    @Shadow
    @Final
    private Scoreboard scoreboard;

    @Inject(
            method = "onScoreboardObjectiveUpdate(Lnet/minecraft/network/packet/s2c/play/ScoreboardObjectiveUpdateS2CPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/thread/ThreadExecutor;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = false,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void replaymod$replaceDuplicateReplayObjective(ScoreboardObjectiveUpdateS2CPacket packet, CallbackInfo ci) {
        if (packet.getMode() != ScoreboardObjectiveUpdateS2CPacket.ADD_MODE
                || !replaymod$isCurrentReplayConnection()) {
            return;
        }

        ScoreboardObjective previous = scoreboard.getNullableObjective(packet.getName());
        if (previous != null) {
            // Old scores and display slots are retired too; only subsequent packets can restore them.
            // Vanilla continues below this hook and creates the objective with the new packet's attributes.
            scoreboard.removeObjective(previous);
        }
    }

    @Unique
    private boolean replaymod$isCurrentReplayConnection() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !mc.isOnThread()) {
            return false;
        }

        ClientPlayNetworkHandler self = (ClientPlayNetworkHandler) (Object) this;
        if (mc.getNetworkHandler() != self) {
            return false;
        }
        ClientConnection connection = self.getConnection();
        if (connection == null || connection.getPacketListener() != self
                || !(connection instanceof NetworkManagerAccessor accessor)) {
            return false;
        }

        Channel channel = accessor.getChannel();
        if (!(channel instanceof EmbeddedChannel) || !channel.isOpen() || !channel.isActive()) {
            return false;
        }

        ReplayModReplay replayMod = ReplayModReplay.instance;
        ReplayHandler replayHandler = replayMod == null ? null : replayMod.getReplayHandler();
        if (replayHandler == null) {
            return false;
        }
        ReplaySender sender = replayHandler.getReplaySender();
        ChannelPipeline pipeline = channel.pipeline();
        return sender != null
                && pipeline.get("packet_handler") == connection
                && pipeline.get(ReplayHandler.PACKET_HANDLER_NAME) == sender;
    }
}
