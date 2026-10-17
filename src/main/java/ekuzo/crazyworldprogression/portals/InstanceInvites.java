package ekuzo.crazyworldprogression.portals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Invites into a private instance (raid or not) that is already running. Accept with /party accept or via its portal. */
public final class InstanceInvites {
    private static final long INVITE_TICKS = 5L * 60L * 20L;
    /** How close to the entrance portal an invited player must be to accept. */
    public static final double ACCEPT_DISTANCE = 8.0;
    private static final Map<MinecraftServer, Map<UUID, Invite>> INVITES = new WeakHashMap<>();

    private InstanceInvites() { }

    private record Invite(Identifier slot, long instanceCreated, Identifier portalId, UUID inviter, long deadlineTick) { }

    /** A player fits when they already belong to the roster or a slot is free. */
    static boolean hasRoom(List<String> admitted, String playerId, int maxPlayers) {
        return admitted.contains(playerId) || admitted.size() < maxPlayers;
    }

    public static void invite(ServerPlayer inviter, ServerPlayer target) {
        MinecraftServer server = inviter.level().getServer();
        PortalData.StoredInstance instance = PortalService.currentInstance(inviter);
        if (instance == null || instance.admittedPlayers().isEmpty())
            throw new IllegalStateException("You can only invite from inside a running dungeon");
        if (!instance.admittedPlayers().contains(inviter.getUUID().toString()))
            throw new IllegalStateException("Only party members can invite");
        if (instance.admittedPlayers().contains(target.getUUID().toString()))
            throw new IllegalStateException(target.getGameProfile().name() + " is already in this instance");
        PortalDefinition portal = PortalService.portal(server, instance.portalId());
        if (portal == null || !hasRoom(instance.admittedPlayers(), target.getUUID().toString(),
                portal.admission().maxPlayers()))
            throw new IllegalStateException("The instance is full");
        INVITES.computeIfAbsent(server, ignored -> new HashMap<>()).put(target.getUUID(), new Invite(
                instance.slotDimension(), instance.createdEpochMillis(), instance.portalId(), inviter.getUUID(),
                server.getTickCount() + INVITE_TICKS));
        String name = inviter.getGameProfile().name();
        target.sendSystemMessage(directions(target, portal).withStyle(ChatFormatting.YELLOW));
        target.sendSystemMessage(Component.literal(name + " invited you to " + portal.effectiveDisplayName()
                        + ". Walk into its entrance portal, or click when you are there: ")
                .append(Component.literal("[Join]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/party accept " + name))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("/party accept " + name))))));
        inviter.sendSystemMessage(Component.literal("Invited " + target.getGameProfile().name() + " to join you."));
    }

    /** The running instance this player was invited into for that portal, or null. */
    static PortalData.StoredInstance invitedInstance(MinecraftServer server, UUID playerId, Identifier portalId) {
        Invite invite = valid(server, playerId);
        return invite == null || !invite.portalId().equals(portalId) ? null : live(server, invite);
    }

    static void consume(MinecraftServer server, UUID playerId) {
        Map<UUID, Invite> invites = INVITES.get(server);
        if (invites != null) invites.remove(playerId);
    }

    public static boolean hasInviteFrom(MinecraftServer server, UUID playerId, UUID inviter) {
        Invite invite = valid(server, playerId);
        return invite != null && invite.inviter().equals(inviter);
    }

    public static void accept(ServerPlayer player, ServerPlayer inviter) {
        MinecraftServer server = player.level().getServer();
        Invite invite = valid(server, player.getUUID());
        if (invite == null || !invite.inviter().equals(inviter.getUUID()))
            throw new IllegalStateException("No invite from " + inviter.getGameProfile().name());
        PortalData.StoredInstance instance = live(server, invite);
        if (instance == null) {
            consume(server, player.getUUID());
            throw new IllegalStateException("That instance has ended");
        }
        PortalDefinition portal = PortalService.portal(server, invite.portalId());
        if (portal == null) throw new IllegalStateException("That instance's entrance portal no longer exists");
        if (!player.level().dimension().identifier().equals(portal.dimension())
                || !portal.isNear(player.position().add(0.0, 0.9, 0.0), ACCEPT_DISTANCE)) {
            player.sendSystemMessage(directions(player, portal).withStyle(ChatFormatting.YELLOW));
            return;
        }
        consume(server, player.getUUID());
        PortalService.joinRunningInstance(player, instance);
    }

    /** "Go to the entrance portal of X at X 120, Y 64, Z -40 in the Overworld (85 blocks away) to join." */
    static net.minecraft.network.chat.MutableComponent directions(ServerPlayer player, PortalDefinition portal) {
        long x = Math.round(portal.centerX()), y = Math.round(portal.centerY()), z = Math.round(portal.centerZ());
        Component dimension = dimensionName(portal.dimension());
        if (player.level().dimension().identifier().equals(portal.dimension())) {
            long distance = Math.round(Math.sqrt(player.distanceToSqr(portal.centerX(), portal.centerY(), portal.centerZ())));
            return Component.translatableWithFallback("message.crazy-world-progression.invite.go_to_portal",
                    "Go to the entrance portal of %s at X %s, Y %s, Z %s in %s (%s blocks away) to join.",
                    portal.effectiveDisplayName(), x, y, z, dimension, distance);
        }
        return Component.translatableWithFallback("message.crazy-world-progression.invite.go_to_portal_elsewhere",
                "Go to the entrance portal of %s at X %s, Y %s, Z %s in %s to join.",
                portal.effectiveDisplayName(), x, y, z, dimension);
    }

    private static Component dimensionName(Identifier dimension) {
        return switch (dimension.toString()) {
            case "minecraft:overworld" -> Component.translatableWithFallback("message.crazy-world-progression.dimension.overworld", "the Overworld");
            case "minecraft:the_nether" -> Component.translatableWithFallback("message.crazy-world-progression.dimension.nether", "the Nether");
            case "minecraft:the_end" -> Component.translatableWithFallback("message.crazy-world-progression.dimension.end", "the End");
            default -> Component.literal(dimension.toString());
        };
    }

    private static Invite valid(MinecraftServer server, UUID playerId) {
        Invite invite = INVITES.getOrDefault(server, Map.of()).get(playerId);
        return invite == null || invite.deadlineTick() < server.getTickCount() ? null : invite;
    }

    private static PortalData.StoredInstance live(MinecraftServer server, Invite invite) {
        PortalData.StoredInstance instance = PortalData.get(server).instances().get(invite.slot().toString());
        return instance != null && instance.createdEpochMillis() == invite.instanceCreated() ? instance : null;
    }
}
