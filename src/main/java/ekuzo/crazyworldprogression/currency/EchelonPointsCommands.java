package ekuzo.crazyworldprogression.currency;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;

public class EchelonPointsCommands {

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            register(dispatcher);
        });
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var baseCommand = Commands.literal("echelonpoints")
                // Player self check: /echelonpoints or /echelonpoints balance
                .executes(EchelonPointsCommands::checkSelf)
                .then(Commands.literal("balance")
                        .executes(EchelonPointsCommands::checkSelf)
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(EchelonPointsCommands::checkOther)
                        )
                )
                // Admin give: /echelonpoints give <targets> <amount>
                .then(Commands.literal("give")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(EchelonPointsCommands::givePoints)
                                )
                        )
                )
                // Admin take: /echelonpoints take <targets> <amount>
                .then(Commands.literal("take")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(EchelonPointsCommands::takePoints)
                                )
                        )
                )
                // Admin set: /echelonpoints set <targets> <amount>
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(EchelonPointsCommands::setPoints)
                                )
                        )
                );

        dispatcher.register(baseCommand);

        // Alias: /ep
        var aliasCommand = Commands.literal("ep")
                .executes(EchelonPointsCommands::checkSelf)
                .then(Commands.literal("balance")
                        .executes(EchelonPointsCommands::checkSelf)
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(EchelonPointsCommands::checkOther)
                        )
                )
                .then(Commands.literal("give")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(EchelonPointsCommands::givePoints)
                                )
                        )
                )
                .then(Commands.literal("take")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(EchelonPointsCommands::takePoints)
                                )
                        )
                )
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(EchelonPointsCommands::setPoints)
                                )
                        )
                );

        dispatcher.register(aliasCommand);
    }

    private static int checkSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        EchelonPointsData data = EchelonPointsData.get(source.getServer());
        int current = data.getPoints(player.getUUID());

        source.sendSuccess(() -> Component.literal("You have ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(String.valueOf(current)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" Echelon Points.").withStyle(ChatFormatting.AQUA)), false);

        return current;
    }

    private static int checkOther(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "target");
        EchelonPointsData data = EchelonPointsData.get(source.getServer());

        for (NameAndId profile : profiles) {
            int current = data.getPoints(profile.id());
            source.sendSuccess(() -> Component.literal(profile.name() + " has ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(String.valueOf(current)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal(" Echelon Points.").withStyle(ChatFormatting.AQUA)), false);
            return current;
        }
        return 0;
    }

    private static int givePoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        EchelonPointsData data = EchelonPointsData.get(source.getServer());

        for (NameAndId profile : profiles) {
            int newBalance = data.addPoints(profile.id(), amount);

            source.sendSuccess(() -> Component.literal("Gave ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(String.valueOf(amount)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal(" Echelon Points to " + profile.name() + ". (New balance: " + newBalance + ")")
                            .withStyle(ChatFormatting.GREEN)), true);

            ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayer(profile.id());
            if (targetPlayer != null) {
                targetPlayer.sendSystemMessage(Component.literal("You received ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(String.valueOf(amount)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal(" Echelon Points! (Total: " + newBalance + ")").withStyle(ChatFormatting.AQUA)));
            }
        }

        return profiles.size();
    }

    private static int takePoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        EchelonPointsData data = EchelonPointsData.get(source.getServer());

        for (NameAndId profile : profiles) {
            int newBalance = data.removePoints(profile.id(), amount);

            source.sendSuccess(() -> Component.literal("Took ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal(String.valueOf(amount)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal(" Echelon Points from " + profile.name() + ". (New balance: " + newBalance + ")")
                            .withStyle(ChatFormatting.RED)), true);

            ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayer(profile.id());
            if (targetPlayer != null) {
                targetPlayer.sendSystemMessage(Component.literal(amount + " Echelon Points were removed from your balance. (Total: " + newBalance + ")")
                        .withStyle(ChatFormatting.RED));
            }
        }

        return profiles.size();
    }

    private static int setPoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        EchelonPointsData data = EchelonPointsData.get(source.getServer());

        for (NameAndId profile : profiles) {
            data.setPoints(profile.id(), amount);

            source.sendSuccess(() -> Component.literal("Set ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(profile.name() + "'s ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("Echelon Points to " + amount + ".")
                            .withStyle(ChatFormatting.GREEN)), true);

            ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayer(profile.id());
            if (targetPlayer != null) {
                targetPlayer.sendSystemMessage(Component.literal("Your Echelon Points balance was set to " + amount + ".")
                        .withStyle(ChatFormatting.AQUA));
            }
        }

        return profiles.size();
    }
}