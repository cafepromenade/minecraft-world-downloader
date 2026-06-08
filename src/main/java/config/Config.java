package config;

import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import game.data.WorldManager;
import game.data.registries.RegistryLoader;
import game.data.registries.RegistryManager;
import game.protocol.Protocol;
import game.protocol.ProtocolVersionHandler;
import gui.GuiManager;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Platform;
import org.apache.commons.lang3.SystemUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import packets.builder.PacketBuilder;
import proxy.ConnectionDetails;
import proxy.ConnectionManager;
import proxy.PacketInjector;
import proxy.auth.AuthDetails;
import proxy.auth.AuthenticationMethod;
import proxy.auth.MicrosoftAuthHandler;
import util.LocalDateTimeAdapter;
import util.PathUtils;

public class Config {
    private static final int DEFAULT_VERSION = 340;
    private static Path configPath;

    private static PacketInjector injector;
    private static PacketInjector serverBoundInjector;
    private static Config instance;

    // fields marked transient so they are not written to JSON file
    private transient ProtocolVersionHandler versionHandler;
    private transient String gameVersion;
    private transient int protocolVersion = DEFAULT_VERSION;
    private transient int dataVersion;
    private transient ConnectionDetails connectionDetails;

    private transient boolean isStarted = false;
    private transient boolean guiOnlyMode = true;

    private transient boolean debugWriteChunkNbt;
    private transient boolean debugTrackEvents = false;
    private transient VersionReporter versionReporter;

    private MicrosoftAuthHandler microsoftAuth;
    private AuthDetails manualAuth;
    private AuthenticationMethod authMethod = AuthenticationMethod.AUTOMATIC;

    public Config() {
        this.versionReporter = new VersionReporter(0);
    }

    public static void setInstance(Config config) {
        instance = config;
    }

    /**
     * Try to read config from file if it exists, otherwise return a new Config object.
     */
    private static Config createConfig() {
        try {
            File file = configPath.toFile();
            if (file.exists() && file.isFile()) {
                Config config;
                try (FileReader reader = new FileReader(file); JsonReader jsonReader = new JsonReader(reader)) {
                    config = new GsonBuilder()
                        .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                        .create()
                        .fromJson(jsonReader, Config.class);
                }

                return Objects.requireNonNullElseGet(config, () -> new Config());
            }
        } catch (Exception ex) {
            System.out.println("Cannot read " + configPath.toString());
            ex.printStackTrace();
        }
        return new Config();
    }

    public static void init(String[] args) {
        configPath = PathUtils.toPath("cache", "config.json");

        instance = createConfig();
        CmdLineParser parser = new CmdLineParser(instance);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            instance.showHelp = true;
        }

        if (instance.showHelp) {
            System.out.println("When running this application without the -s parameter, the settings UI will be \n" +
                                "shown on startup. When running with --no-gui, the -s parameter is required.\n");

            System.out.println("Available parameters:");
            parser.printUsage(System.out);
            System.exit(1);
        }

        if (instance.clearSettings) {
            clearSettings();
            System.exit(1);
        }

        instance.settingsComplete();
    }

    public static ConnectionDetails getConnectionDetails() {
        return instance.connectionDetails;
    }

    public static void setProtocolVersion(int protocolVersion) {
        instance.protocolVersion = protocolVersion;
        instance.versionReporter = new VersionReporter(protocolVersion);
        instance.dataVersion = instance.versionReporter.getDataVersion();

        try {
            WorldManager.getInstance().loadLevelData();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static boolean inGuiMode() {
        return instance.guiOnlyMode;
    }

    public static Config getInstance() {
        return instance;
    }

    public static int getCenterX() {
        return instance.centerX;
    }
    public static int getCenterZ() {
        return instance.centerZ;
    }

    public static boolean writeChunksAsNbt() {
        return instance.debugWriteChunkNbt;
    }

    public static void toggleWriteChunkNbt() {
        instance.debugWriteChunkNbt = !instance.debugWriteChunkNbt;
    }

    public static void disableSettingsGui() {
        instance.guiOnlyMode = false;
    }

    public static boolean trackEvents() {
        return instance.debugTrackEvents;
    }

    public static String getUsername() {
        return instance.username;
    }

    public static void handleErrorOutput() {
        instance.handleGuiOnlyMode();
    }

    public boolean startWithSettings() {
        return guiOnlyMode;
    }


    public void settingsComplete() {
        GuiManager.setConfig(this);

        if (guiOnlyMode && !GuiManager.isStarted()) {
            GuiManager.loadSceneSettings();
            return;
        }

        // auth
        boolean hasAccessToken = this.accessToken != null && !this.accessToken.equals("");
        if (hasAccessToken) {
            this.manualAuth = AuthDetails.fromAccessToken(accessToken);
            this.authMethod = AuthenticationMethod.MANUAL;
        }

        // round to regions
        centerX = (centerX >> 9) << 9;
        centerZ = (centerZ >> 9) << 9;

        WorldManager.getInstance().setWorldManagerVariables(markNewChunks, writeChunks());
        WorldManager.getInstance().updateExtendedRenderDistance(extendedRenderDistance);

        writeSettings();

        if (isStarted) {
            return;
        }

        isStarted = true;

        versionHandler = ProtocolVersionHandler.getInstance();
        connectionDetails = new ConnectionDetails(server, portLocal, !disableSrvLookup);

        if (!disableGui) {
            GuiManager.loadSceneMap();
        }

        new ConnectionManager().startProxy();
    }

    private void writeSettings() {
        try {
            // clear other auth settings
            switch (authMethod) {
                case AUTOMATIC -> {
                    manualAuth = null;
                    microsoftAuth = null;
                }
                case MICROSOFT -> manualAuth = null;
                case MANUAL -> microsoftAuth = null;
            }

            String contents = new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .setPrettyPrinting().create().toJson(this);
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, contents);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void save() {
        instance.writeSettings();
    }

    public static void clearSettings() {
        try {
            if (configPath.toFile().exists()) {
                configPath.toFile().deleteOnExit();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.err.println("Unable to delete settings.");
        }
    }

    private void handleGuiOnlyMode() {
        if (System.console() != null) {
            return;
        }

        if (!devMode && !forceConsoleOutput) {
            System.out.println("Application seems to be running without console. Redirecting error output to GUI. " +
                    "If this is not desired, run with --force-console.");

            Platform.runLater(GuiManager::redirectErrorOutput);
        }
    }

    public boolean isStarted() {
        return isStarted;
    }

    /**
     * Get the platform-specific default path for the Minecraft installation directory.
     * @return the path as a string
     */
    public static String getDefaultMinecraftPath() {
        if (SystemUtils.IS_OS_WINDOWS) {
            String path = Paths.get("%appdata%", ".minecraft").toString();

            // handle common %APPDATA% env variable for Windows
            if (path.toUpperCase().contains("%APPDATA%") && System.getenv("appdata") != null) {
                String appdataPath = System.getenv("appdata").replace("\\", "\\\\");
                path = path.replaceAll("(?i)%APPDATA%", appdataPath);
            }

            return path;
        } else if (SystemUtils.IS_OS_LINUX) {
            return Paths.get(System.getProperty("user.home"), ".minecraft").toString();
        } else if (SystemUtils.IS_OS_UNIX) {
            return Paths.get("/Users/", System.getProperty("user.name"), "/Library/Application Support/minecraft").toString();
        } else {
            return ".minecraft";
        }
    }

    public static Protocol getGameProtocol() {
        Protocol p = instance.versionHandler.getProtocolByProtocolVersion(instance.protocolVersion);
        instance.dataVersion = p.getDataVersion();
        instance.gameVersion = p.getVersion();
        instance.versionReporter = new VersionReporter(instance.protocolVersion);

        new Thread(() -> loadVersionRegistries(p)).start();

        System.out.println("Using protocol of game version " + p.getVersion() + " (" + instance.protocolVersion + ")");
        return p;
    }

    private static void loadVersionRegistries(Protocol p) {
        try {
            RegistryLoader loader = RegistryLoader.forVersion(p.getVersion());
            if (loader == null) { return; }

            WorldManager.getInstance().setEntityMap(loader.generateEntityNames());

            RegistryManager.getInstance().setRegistries(loader);

            WorldManager.getInstance().startSaveService();

            loader.clean();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Pre-scan mod JARs in the background so modded block texture colors are ready when chunks arrive.
        if (moddedBlockColors()) {
            game.data.chunk.palette.ModdedBlockColorExtractor.getInstance().preloadAsync();
        }
    }

    private boolean writeChunks() {
        return !disableWriteChunks;
    }

    /**
     * Packet injector allows new packets to be sent to the client.
     */
    public static void registerPacketInjector(PacketInjector injector) {
        Config.injector = injector;
    }

    public static PacketInjector getPacketInjector() {
        return injector;
    }

    /**
     * Serverbound packet injector: lets the proxy originate packets toward the SERVER
     * (used by the auto-open-containers feature).
     */
    public static void registerServerBoundInjector(PacketInjector injector) {
        Config.serverBoundInjector = injector;
    }

    public static PacketInjector getServerBoundInjector() {
        return serverBoundInjector;
    }


    @Option(name = "--help", aliases = {"-h", "help", "-help", "--h"},
            usage = "Show this help message.")
    public transient boolean showHelp;

    // parameters
    @Option(name = "--server", aliases = "-s", handler = ServerHandler.class,
            usage = "The address of the remote server to connect to. Hostname or IP address (without port).")
    public String server;

    @Option(name = "--token", aliases = "-t",
            usage = "Minecraft access token. Found in launcher_accounts.json by default.")
    public transient String accessToken;

    @Option(name = "--username", aliases = "-u",
            usage = "Your Minecraft username.")
    public transient String username;

    @Option(name = "--local-port", aliases = "-l",
            usage = "The port on which the world downloader's server will run.")
    public int portLocal = 25565;

    @Option(name = "--extended-render-distance", aliases = "-r",
            usage = "When set, send downloaded chunks to client to extend render distance to given amount.")
    public int extendedRenderDistance = 0;

    @Option(name = "--extended-render-pace",
            usage = "Milliseconds to pause between each re-sent chunk when extending render distance. "
                    + "Lower = chunks appear faster but can stutter; higher = smoother but slower to fill "
                    + "in. Default 6 (steady drip; 0 = send as fast as possible).")
    public int extendedRenderPaceMs = 6;

    @Option(name = "--seed",
            usage = "Numeric level seed for output world.")
    public long levelSeed = 0;

    @Option(name = "--output", aliases = "-o",
            usage = "The world output directory. If the world already exists, it will be updated.")
    public String worldOutputDir = "world";

    @Option(name = "--center-x", depends = "--center-z",
            usage = "Offsets output world. Given center X coordinate will be put at world origin (0, 0). Rounded to multiples of 512 blocks.")
    public int centerX = 0;

    @Option(name = "--center-z", depends = "--center-x",
            usage = "Offsets output world. Given center Z coordinate will be put at world origin (0, 0). Rounded to multiples of 512 blocks.")
    public int centerZ = 0;

    @Option(name = "--render-players",
            usage = "Show other players in the overview map.")
    public boolean renderOtherPlayers = false;

    @Option(name = "--no-gui", depends = "--server",
            usage = "Disable the GUI")
    public transient boolean disableGui = false;

    @Option(name = "--render-map",
            usage = "Render the overview map to PNG region tiles under <output>/overview so the web "
                    + "console can display a live map. Enabled automatically in --no-gui mode.")
    public boolean renderMap = false;

    @Option(name = "--disable-map-render",
            usage = "Do not render the overview map to disk, even when running headless (--no-gui).")
    public boolean disableMapRender = false;

    @Option(name = "--mark-new-chunks",
            usage = "Mark new chunks in an orange outline.")
    public transient boolean markNewChunks = false;

    @Option(name = "--disable-chunk-saving",
            usage = "Disable writing chunks to disk, mostly for debugging purposes.")
    public  boolean disableWriteChunks = false;

    @Option(name = "--modded-block-colors",
            usage = "Render modded (non-minecraft:) blocks on the map by extracting texture colors from "
                    + "mod JARs in .minecraft/mods, falling back to a deterministic per-name color.")
    public boolean moddedBlockColors = true;

    @Option(name = "--disable-modded-block-colors",
            usage = "Turn off modded-block map colouring (on by default). Modded blocks then stay "
                    + "transparent on the map like other unknown blocks.")
    public boolean disableModdedBlockColors = false;

    @Option(name = "--auto-open-containers",
            usage = "EXPERIMENTAL: automatically open nearby containers (within reach, one at a time, "
                    + "rate-limited) to record their contents as you move. May trip server anti-cheat.")
    public boolean autoOpenContainers = false;

    @Option(name = "--auto-open-delay",
            usage = "Minimum milliseconds between auto-opened containers (default 400). Higher = safer.")
    public int autoOpenDelayMs = 400;

    @Option(name = "--auto-open-reach",
            usage = "Max distance (blocks) to a container for auto-open; keep at/below survival reach (default 4.0).")
    public double autoOpenReach = 4.0;

    @Option(name = "--auto-open-state",
            usage = "File that records which containers were already auto-opened, so a block is never "
                    + "re-opened (even across restarts). Default: 'auto-open-attempted.txt' beside the world "
                    + "folder (outside it, so it persists on the host). Set an absolute path to store it anywhere.")
    public String autoOpenStateFile = "";

    @Option(name = "--auto-open-log",
            usage = "File to append a human-readable list of items captured by --auto-open-containers. "
                    + "Default: 'auto-open-items.log' beside the world folder (outside it, so it persists "
                    + "on the host). Set an absolute path to store it elsewhere.")
    public String autoOpenLogFile = "";

    @Option(name = "--auto-open-allow-chest-near-players",
            usage = "By default the auto-open sweep will NOT open a chest, trapped chest, barrel or "
                    + "shulker box while another player is within --auto-open-player-radius (to avoid "
                    + "opening containers in view of others). Pass this flag to open them anyway. Only "
                    + "affects chests/trapped chests/barrels/shulker boxes; all other container types "
                    + "always auto-open.")
    public boolean autoOpenAllowChestNearPlayers = false;

    @Option(name = "--auto-open-player-radius",
            usage = "Radius (blocks) for the chest 'other player nearby' check (default 100).")
    public double autoOpenPlayerRadius = 100.0;

    @Option(name = "--auto-open-gamemodes",
            usage = "Which gamemodes the auto-open sweep runs in: 'all' (default, any mode incl. survival), "
                    + "or a comma list of survival,creative,adventure,spectator. A restricted list only "
                    + "activates once that gamemode is observed (e.g. after switching into spectator).")
    public String autoOpenGamemodes = "all";

    @Option(name = "--container-message-format",
            usage = "Template for the saved-container action-bar message (not hardcoded). Placeholders: "
                    + "{type} = block type (e.g. chest), {count} = slots with items, {x} {y} {z} = block "
                    + "coordinates. Default: \"{type} ({count}) - {x} {y} {z}\".")
    public String containerMessageFormat = "{type} ({count}) - {x} {y} {z}";

    @Option(name = "--auto-reply",
            usage = "EXPERIMENTAL: when an incoming chat message's yellow text matches --auto-reply-trigger, "
                    + "send that same message's red text back to the server as a chat message. Sends REAL chat; "
                    + "servers enforcing secure chat may reject it. Use only where permitted.")
    public boolean autoReply = false;

    @Option(name = "--auto-reply-trigger",
            usage = "The exact yellow text that triggers an auto-reply (surrounding spaces/quotes ignored). "
                    + "Required for --auto-reply to do anything. E.g. \"You have been warned by Console for\".")
    public String autoReplyTrigger = "";

    @Option(name = "--auto-reply-delay",
            usage = "Minimum milliseconds between auto-replies (default 1500), to avoid chat spam / kicks.")
    public int autoReplyDelayMs = 1500;

    @Option(name = "--auto-reply-trigger-color",
            usage = "Colour of the text that must match --auto-reply-trigger (default 'yellow'). Can be any "
                    + "Minecraft colour name so the feature works for differently-coloured messages.")
    public String autoReplyTriggerColor = "yellow";

    @Option(name = "--auto-reply-color",
            usage = "Colour of the text that is sent back as the reply (default 'red').")
    public String autoReplyColor = "red";

    @Option(name = "--disable-world-gen",
            usage = "Set world type to a superflat void to prevent new chunks from being added.")
    public boolean disableWorldGen = false;

    @Option(name = "--disable-srv-lookup",
            usage = "Disable checking for true address using DNS service records")
    public boolean disableSrvLookup = false;

    @Option(name = "--disable-mark-unsaved",
            usage = "Disable marking unsaved chunks in red on the map")
    public boolean disableMarkUnsavedChunks = false;

    @Option(name = "--mark-old-chunks",
        usage = "Grey out old chunks on the map")
    public boolean markOldChunks = true;

    @Option(name = "--ignore-block-changes",
            usage = "Ignore changes to chunks after they have been loaded.")
    public boolean ignoreBlockChanges = false;

    @Option(name = "--dev-mode",
            usage = "Enable developer mode")
    private transient boolean devMode = false;

    @Option(name = "--force-console",
            usage = "Never redirect console output to GUI")
    private transient boolean forceConsoleOutput = false;

    @Option(name = "--clear-settings",
            usage = "Clear settings by deleting config.json file, then exit.")
    private transient boolean clearSettings = false;

    @Option(name = "--disable-messages",
            usage = "Disable various info messages (e.g. chest saving).")
    public boolean disableInfoMessages = false;

    @Option(name = "--draw-extended-chunks",
            usage = "Draw extended chunks to map")
    public boolean drawExtendedChunks = false;

    @Option(name = "--enable-cave-mode",
            usage = "Enable automatically switching to cave render mode when underground.")
    public boolean enableCaveRenderMode = false;

    @Option(name = "--enable-voice-proxy",
            usage = "Transparently proxy Simple Voice Chat / PlasmoVoice UDP traffic through localhost so "
                    + "voice works while connected via the downloader proxy. Auto-detects the voice port "
                    + "from plugin-channel packets and rewrites the advertised host to the proxy.")
    public boolean enableVoiceProxy = false;

    // not really important enough to have an option for, can change it in config file
    public boolean smoothZooming = true;

    // getters
    public static int getExtendedRenderDistance() {
        return instance.extendedRenderDistance;
    }

    /** Milliseconds to pause between each re-sent extended chunk (smooths delivery; 0 = no pause). */
    public static int extendedRenderPaceMs() {
        return Math.max(0, instance.extendedRenderPaceMs);
    }

    public static long getLevelSeed() {
        return instance.levelSeed;
    }

    public static String getWorldOutputDir() {
        return instance.worldOutputDir;
    }

    /**
     * Whether to render the overview map to PNG tiles on disk (for the web console). On by default in
     * headless mode (--no-gui), or when forced with --render-map; can be turned off with
     * --disable-map-render.
     */
    public static boolean renderOverviewMap() {
        return (instance.renderMap || instance.disableGui) && !instance.disableMapRender;
    }

    /**
     * Resolve a file that, by convention, lives BESIDE the world output folder (in its parent dir) so it
     * persists outside the world data itself (and outside Docker's ephemeral layer). Shared by the
     * --auto-open-* sidecar files. If {@code customPath} is set it is used verbatim; otherwise
     * {@code defaultName} is resolved next to the world folder. Returns null if no world dir is set.
     */
    public static Path resolveBesideWorldFile(String customPath, String defaultName) {
        if (customPath != null && !customPath.isEmpty()) {
            return Paths.get(customPath);
        }
        String dir = getWorldOutputDir();
        if (dir == null || dir.isEmpty()) {
            return null;
        }
        Path world = Paths.get(dir).toAbsolutePath();
        Path parent = world.getParent();
        return (parent != null ? parent : world).resolve(defaultName);
    }

    public static boolean isInDevMode() {
        return instance.devMode;
    }

    public static int getDataVersion() {
        return instance.dataVersion;
    }

    public static String getGameVersion() {
        return instance.gameVersion;
    }

    public static boolean renderOtherPlayers() { return instance.renderOtherPlayers; }

    public static boolean moddedBlockColors() { return instance.moddedBlockColors && !instance.disableModdedBlockColors; }

    public static boolean autoOpenContainers() { return instance.autoOpenContainers; }

    public static int autoOpenDelayMs() { return Math.max(50, instance.autoOpenDelayMs); }

    public static double autoOpenReach() { return instance.autoOpenReach; }

    public static String autoOpenStateFile() { return instance.autoOpenStateFile; }

    public static String autoOpenLogFile() { return instance.autoOpenLogFile; }

    /** Whether to skip auto-opening chests/trapped chests/barrels/shulker boxes while another player is nearby (default true). */
    public static boolean autoOpenSkipChestNearPlayers() { return !instance.autoOpenAllowChestNearPlayers; }

    public static double autoOpenPlayerRadius() { return instance.autoOpenPlayerRadius; }

    /**
     * Allowed gamemodes for the auto-open sweep, or null for "all gamemodes" (no gate).
     * Names map to ids: survival=0, creative=1, adventure=2, spectator=3.
     */
    public static java.util.Set<Integer> autoOpenGamemodes() {
        String v = instance.autoOpenGamemodes;
        if (v == null || v.isBlank() || v.equalsIgnoreCase("all")) {
            return null;
        }
        java.util.Set<Integer> set = new java.util.HashSet<>();
        for (String part : v.split(",")) {
            switch (part.trim().toLowerCase()) {
                case "survival": case "0": set.add(0); break;
                case "creative": case "1": set.add(1); break;
                case "adventure": case "2": set.add(2); break;
                case "spectator": case "3": set.add(3); break;
                default: break;
            }
        }
        return set.isEmpty() ? null : set;
    }

    /**
     * Template for the saved-container action-bar message, with {type} {count} {x} {y} {z} placeholders.
     * Falls back to a sensible default if cleared, so the message is never accidentally blank.
     */
    public static String containerMessageFormat() {
        String f = instance.containerMessageFormat;
        return (f == null || f.isBlank()) ? "{type} ({count}) - {x} {y} {z}" : f;
    }

    public static boolean autoReply() { return instance.autoReply; }

    public static String autoReplyTrigger() { return instance.autoReplyTrigger; }

    public static int autoReplyDelayMs() { return Math.max(250, instance.autoReplyDelayMs); }

    public static String autoReplyTriggerColor() {
        return instance.autoReplyTriggerColor == null || instance.autoReplyTriggerColor.isBlank()
                ? "yellow" : instance.autoReplyTriggerColor;
    }

    public static String autoReplyColor() {
        return instance.autoReplyColor == null || instance.autoReplyColor.isBlank()
                ? "red" : instance.autoReplyColor;
    }

    public static VersionReporter versionReporter() {
        return instance.versionReporter;
    }

    public static AuthDetails getManualAuthDetails() {
        return instance.manualAuth;
    }

    public static void setManualAuthDetails(AuthDetails details) {
        instance.manualAuth = details;
    }

    // inverted boolean getters
    public static boolean isWorldGenEnabled() {
        return !instance.disableWorldGen;
    }

    public static boolean markUnsavedChunks() {
        return !instance.disableMarkUnsavedChunks;
    }

    public static boolean handleBlockChanges() {
        return !instance.ignoreBlockChanges;
    }

    public static boolean sendInfoMessages() { return !instance.disableInfoMessages; }

    public static boolean drawExtendedChunks() { return instance.drawExtendedChunks; }

    public static boolean smoothZooming() {
        return instance.smoothZooming;
    }

    public static boolean markOldChunks() {
        return instance.markOldChunks;
    }
    public static boolean enableVoiceProxy() { return instance.enableVoiceProxy; }

    public static boolean enableCaveRenderMode() {
        return instance.enableCaveRenderMode;
    }

    public static MicrosoftAuthHandler getMicrosoftAuth() {
        return instance.microsoftAuth;
    }

    public static void setMicrosoftAuth(MicrosoftAuthHandler microsoftAuth) {
        instance.microsoftAuth = microsoftAuth;
    }

    public static AuthenticationMethod getAuthMethod() {
        return instance.authMethod;
    }

    public static void setAuthMethod(AuthenticationMethod authMethod) {
        instance.authMethod = authMethod;
    }
}

