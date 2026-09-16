package dev.vvh.mekasuitarcana.runtime;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Dev-only command adapter. The runtime-test jar auto-registers this on the game bus. */
@EventBusSubscriber(modid = "mekasuitarcana", bus = EventBusSubscriber.Bus.GAME)
public final class RuntimeVerificationCommand {
    private RuntimeVerificationCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event);
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mekasuitarcana_runtime_test")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("auto").executes(context -> {
                    final RuntimeVerificationHarness.AutoReport report;
                    try {
                        report = RuntimeVerificationHarness.runAutomatic(context.getSource().getServer());
                    } catch (RuntimeException failure) {
                        failure.printStackTrace();
                        context.getSource().sendFailure(Component.literal("FAIL arcana_runtime_auto " + failure));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal(
                            "AUTO syntheticFakePlayer=" + report.syntheticFakePlayer()
                                    + ", install=" + report.installationPath()
                                    + ", support=" + report.moduleSupport()
                                    + ", powered=" + report.poweredManaAndEnergy()
                                    + ", castPayment=" + report.castPaymentCharged()
                                    + ", caps=" + report.amplificationCaps()
                                    + ", serverCaps=" + report.serverCaps()
                                    + ", noDrySuitBuff=" + report.noDrySuitBuff()
                                    + ", boundary=" + report.proofBoundary()), true);
                    return report.installationPath().passed() && report.moduleSupport().passed()
                            && report.poweredManaAndEnergy().passed() && report.castPaymentCharged()
                            && report.amplificationCaps().passed() && report.serverCaps().passed()
                            && report.noDrySuitBuff().passed() ? 1 : 0;
                }))
                .then(Commands.literal("snapshot").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    context.getSource().sendSuccess(() -> Component.literal(RuntimeVerificationHarness.snapshot(player).toString()), false);
                    return 1;
                }))
                .then(Commands.literal("tick")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 200))
                                .executes(context -> {
                                    var player = context.getSource().getPlayerOrException();
                                    int ticks = IntegerArgumentType.getInteger(context, "ticks");
                                    context.getSource().sendSuccess(() -> Component.literal(RuntimeVerificationHarness.tick(player, ticks).toString()), false);
                                    return 1;
                                })))
                .then(Commands.literal("cast-payment")
                        .then(Commands.argument("school", StringArgumentType.word())
                                .then(Commands.argument("mana", IntegerArgumentType.integer(0, 100000))
                                        .executes(context -> {
                                            var player = context.getSource().getPlayerOrException();
                                            var result = RuntimeVerificationHarness.invokeCastPayment(
                                                    player, StringArgumentType.getString(context, "school"),
                                                    IntegerArgumentType.getInteger(context, "mana"));
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "energySpent=" + result.energySpent() + ", before=" + result.before()
                                                            + ", after=" + result.after()), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("native-cast")
                        .then(Commands.argument("spell", StringArgumentType.greedyString())
                                .executes(context -> {
                                    var player = context.getSource().getPlayerOrException();
                                    var result = RuntimeVerificationHarness.attemptNativeCast(
                                            player, StringArgumentType.getString(context, "spell"), 1);
                                    context.getSource().sendSuccess(() -> Component.literal(result.toString()), false);
                                    return 1;
                                })))
                .then(Commands.literal("native-finish")
                        .then(Commands.argument("spell", StringArgumentType.greedyString())
                                .executes(context -> {
                                    var player = context.getSource().getPlayerOrException();
                                    var result = RuntimeVerificationHarness.finishNativeCast(
                                            player, StringArgumentType.getString(context, "spell"), 1);
                                    context.getSource().sendSuccess(() -> Component.literal(result.toString()), false);
                                    return 1;
                                }))));
    }
}
