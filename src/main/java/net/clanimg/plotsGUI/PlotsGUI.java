package net.clanimg.plotsGUI;

import net.clanimg.plotsGUI.cache.PlotCacheStore;
import net.clanimg.plotsGUI.config.GuiConfig;
import net.clanimg.plotsGUI.config.ServerIdentity;
import net.clanimg.plotsGUI.config.Settings;
import net.clanimg.plotsGUI.gui.ItemBuilder;
import net.clanimg.plotsGUI.gui.PlaceholderService;
import net.clanimg.plotsGUI.gui.PlotMenuListener;
import net.clanimg.plotsGUI.gui.PlotMenuService;
import net.clanimg.plotsGUI.listener.PendingActionJoinListener;
import net.clanimg.plotsGUI.messaging.RedisActionChannel;
import net.clanimg.plotsGUI.messaging.RedisActionException;
import net.clanimg.plotsGUI.sync.ActionExecutor;
import net.clanimg.plotsGUI.sync.PlotSquaredSyncModule;
import net.clanimg.plotsGUI.transfer.SimpleCloudTransferService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;

public final class PlotsGUI extends JavaPlugin {

    private RedisActionChannel actionChannel;
    private PlotCacheStore cache;
    private PlotSquaredSyncModule syncModule;
    private ActionExecutor actionExecutor;
    private PlotMenuService menuService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "gui.yml").exists()) {
            saveResource("gui.yml", false);
        }

        Settings settings;
        GuiConfig guiConfig;
        try {
            settings = Settings.load(getConfig());
            guiConfig = GuiConfig.load(
                    YamlConfiguration.loadConfiguration(new File(getDataFolder(), "gui.yml")), getLogger());
        } catch (IllegalArgumentException e) {
            getLogger().severe("Ungueltige Konfiguration: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String localServerName = ServerIdentity.resolve(settings.localServerNameOverride(), getLogger());
        boolean localIsPlotsquaredServer = !settings.plotsquaredServerName().isBlank()
                && settings.plotsquaredServerName().equalsIgnoreCase(localServerName);
        boolean plotSquaredPresent = getServer().getPluginManager().isPluginEnabled("PlotSquared");

        try {
            cache = new PlotCacheStore(settings.database());
            cache.init();
        } catch (SQLException | RuntimeException e) {
            // HikariCP throws an unchecked PoolInitializationException when it cannot reach MySQL at all,
            // rather than the checked SQLException init() itself can throw once a pool exists.
            getLogger().severe("Konnte MySQL-Cache nicht initialisieren: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        actionChannel = new RedisActionChannel(settings.redis(), getLogger());
        try {
            actionChannel.ping();
        } catch (RedisActionException e) {
            getLogger().warning(e.getMessage()
                    + " - Cross-Server-Aktionen funktionieren erst, wenn Redis erreichbar ist.");
        }

        PlaceholderService placeholders = new PlaceholderService(settings.displayTimezone());
        ItemBuilder itemBuilder = new ItemBuilder(this, placeholders);
        menuService = new PlotMenuService(this, guiConfig, cache, itemBuilder, getLogger());
        SimpleCloudTransferService transferService = new SimpleCloudTransferService(settings.simpleCloud());

        getServer().getPluginManager().registerEvents(
                new PlotMenuListener(this, menuService, guiConfig, settings, actionChannel, transferService,
                        localIsPlotsquaredServer, itemBuilder, getLogger()),
                this);

        if (localIsPlotsquaredServer) {
            if (plotSquaredPresent) {
                syncModule = new PlotSquaredSyncModule(this, cache, getLogger());
                syncModule.start();

                getServer().getPluginManager().registerEvents(
                        new PendingActionJoinListener(this, actionChannel, syncModule, getLogger()), this);

                actionExecutor = new ActionExecutor(this, actionChannel, syncModule, getLogger());
                actionExecutor.start();
            } else {
                getLogger().warning("Dieser Server ist als plotsquared_server-name konfiguriert, aber PlotSquared"
                        + " ist nicht installiert/aktiviert. Sync-Modus bleibt aus.");
            }
        }
    }

    @Override
    public void onDisable() {
        if (actionExecutor != null) {
            actionExecutor.stop();
        }
        if (syncModule != null) {
            syncModule.stop();
        }
        if (actionChannel != null) {
            actionChannel.close();
        }
        if (cache != null) {
            cache.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler koennen dieses Menue oeffnen.");
            return true;
        }
        if (menuService == null) {
            player.sendMessage("PlotsGUI ist nicht korrekt geladen.");
            return true;
        }
        menuService.open(player);
        return true;
    }
}
