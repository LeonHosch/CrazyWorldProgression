package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.cleanup.PerformanceMonitor;
import ekuzo.crazyworldprogression.cleanup.TickBudget;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.portals.InstanceBuilds;
import ekuzo.crazyworldprogression.portals.PocketCaptures;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.WorkshopWipes;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The admin panel's Performance page and the /cwp performance chat summary. */
public final class AdminPerformance {
    static final String PAGE = "performance";
    private static final int WORLD_ROWS = 12;

    private AdminPerformance() { }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, List<AdminPanelRegistry.AdminTab> tabs) {
        MinecraftServer server = player.level().getServer();
        PerformanceMonitor.Tick tick = PerformanceMonitor.tick(server);
        TickBudget.Stats budget = TickBudget.stats(server);
        double tickLength = server.tickRateManager().nanosecondsPerTick() / 1e6;
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(row("Server", "Tick time", ms(tick.averageMs()) + " average · " + ms(tick.p95Ms()) + " (95%) · "
                + ms(tick.peakMs()) + " slowest of the last 100 ticks", tone(tick.p95Ms(), tickLength)));
        rows.add(row("Server", "Ticks per second", String.format(Locale.ROOT, "%.1f of %.0f · %d players online",
                tick.tps(), 1000.0 / tickLength, server.getPlayerCount()), tone(tick.averageMs(), tickLength)));
        rows.add(row("CWP block work", "Time per tick", "scripts " + ms(budget.scriptMs()) + " · instance building "
                + ms(budget.creationMs()) + " · cleanup " + ms(budget.cleanupMs()) + " · allowed now " + ms(budget.budgetMs()),
                AdminPanelRegistry.Tone.NORMAL));
        rows.add(row("CWP block work", "Waiting", queues(server), AdminPanelRegistry.Tone.NORMAL));
        List<PerformanceMonitor.World> worlds = PerformanceMonitor.worlds(server);
        int shown = 0;
        for (PerformanceMonitor.World world : worlds) {
            if (shown++ >= WORLD_ROWS) break;
            rows.add(row("Worlds, slowest first", name(server, world.id()), ms(world.averageMs()) + " per tick · peak "
                    + ms(world.peakMs()) + " · " + world.entities() + " entities · " + world.chunks() + " chunks",
                    tone(world.averageMs(), tickLength / 2)));
        }
        if (worlds.size() > WORLD_ROWS) rows.add(row("Worlds, slowest first", "+" + (worlds.size() - WORLD_ROWS)
                + " more", "idle worlds that barely use time", AdminPanelRegistry.Tone.NORMAL));
        return new AdminPanelRegistry.AdminPage(PAGE, "Performance",
                "Where the tick time goes · press ⟳ to update", tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    /** Short version for chat. */
    public static List<Component> chat(MinecraftServer server) {
        PerformanceMonitor.Tick tick = PerformanceMonitor.tick(server);
        TickBudget.Stats budget = TickBudget.stats(server);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("CWP performance").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        lines.add(line("Tick", ms(tick.averageMs()) + " avg, " + ms(tick.p95Ms()) + " p95, " + ms(tick.peakMs())
                + " max · " + String.format(Locale.ROOT, "%.1f TPS", tick.tps())));
        lines.add(line("CWP", "scripts " + ms(budget.scriptMs()) + ", building " + ms(budget.creationMs())
                + ", cleanup " + ms(budget.cleanupMs()) + " (allowed " + ms(budget.budgetMs()) + ")"));
        lines.add(line("Waiting", queues(server)));
        int shown = 0;
        for (PerformanceMonitor.World world : PerformanceMonitor.worlds(server)) {
            if (shown++ >= 5) break;
            lines.add(line(name(server, world.id()), ms(world.averageMs()) + ", " + world.entities() + " entities, "
                    + world.chunks() + " chunks"));
        }
        lines.add(Component.literal("More in /cwp admin → Logs → Performance").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static String queues(MinecraftServer server) {
        long blocks = InstanceBuilds.remainingBlocks(server);
        return InstanceBuilds.pending(server) + " instance build(s)" + (blocks > 0 ? String.format(Locale.ROOT,
                " (%,d blocks left)", blocks) : "") + " · " + PortalService.cleanupCount(server) + " cleanup(s) · "
                + WorkshopWipes.count(server) + " workshop reset(s) · " + PocketCaptures.count(server)
                + " pocket save(s) · " + MobForge.SCRIPT_EXECUTOR.activeRunCount() + " running script(s)";
    }

    /** Readable world names: instances show their portal, workshops their pocket. */
    private static String name(MinecraftServer server, Identifier dimension) {
        for (PortalService.ActiveInstance instance : PortalService.activeInstances(server))
            if (instance.slotDimension().equals(dimension)) return "Instance of " + instance.portalId().getPath()
                    + " (" + dimension.getPath() + ")";
        if (PocketEditorService.isEditorDimension(dimension)) {
            Identifier pocket = PocketEditorService.pocketForDimension(server, dimension);
            return "Workshop " + (pocket == null ? dimension.getPath() : pocket.getPath());
        }
        return dimension.getNamespace().equals("minecraft") ? dimension.getPath() : dimension.toString();
    }

    private static AdminPanelRegistry.AdminEntry row(String section, String title, String detail,
                                                     AdminPanelRegistry.Tone tone) {
        return new AdminPanelRegistry.AdminEntry(title, detail, tone, List.of(), List.of(), section, null);
    }

    private static MutableComponent line(String label, String value) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    /** Green up to half the limit, yellow up to the limit, red above. */
    private static AdminPanelRegistry.Tone tone(double value, double limit) {
        return value > limit ? AdminPanelRegistry.Tone.DANGER : value > limit / 2 ? AdminPanelRegistry.Tone.WARNING
                : AdminPanelRegistry.Tone.GOOD;
    }

    private static String ms(double value) {
        return String.format(Locale.ROOT, value < 10 ? "%.2f ms" : "%.1f ms", value);
    }
}
