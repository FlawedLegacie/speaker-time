package com.speakertime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "Speaker Time",
    internalName = "speaker-time",
    description = "Show chat timestamps in the speaker's shared time zone",
    enabledByDefault = false
)
public class SpeakerTimePlugin extends Plugin
{
    private static final Logger LOG = LoggerFactory.getLogger(SpeakerTimePlugin.class);
    private static final Pattern ANNOUNCEMENT = Pattern.compile("!tz(?::\\s*|\\s+)(.+)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_SPEAKERS = 2048;
    private static final Set<ChatMessageType> PLAYER_CHAT = EnumSet.of(
        ChatMessageType.PUBLICCHAT, ChatMessageType.MODCHAT, ChatMessageType.PRIVATECHAT,
        ChatMessageType.MODPRIVATECHAT, ChatMessageType.PRIVATECHATOUT,
        ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT,
        ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT);
    private static final Map<String, ZoneId> ZONES = ZoneId.getAvailableZoneIds().stream()
        .collect(Collectors.toMap(id -> id.toLowerCase(Locale.ROOT), ZoneId::of, (first, second) -> first));
    private static final Pattern SHORTCUT_ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern SHORTCUT_SEPARATORS = Pattern.compile("[\\s_-]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Map<String, String> CITY_SHORTCUTS = cityShortcuts();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("'['h:mm a']'", Locale.US);

    private static final DateTimeFormatter SHARED_CLOCK = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("h:mm a")
        .toFormatter(Locale.US)
        .withResolverStyle(ResolverStyle.STRICT);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private ClientToolbar toolbar;

    private NavigationButton navigation;
    private volatile Map<String, ZoneId> savedZones = Map.of();

    @Override
    protected void startUp()
    {
        navigation = NavigationButton.builder()
            .tooltip("Speaker Time")
            .icon(ImageUtil.loadImageResource(SpeakerTimePlugin.class, "speaker-time.png"))
            .panel(new SpeakerTimePanel(this))
            .build();
        toolbar.addNavigation(navigation);

        executor.execute(() ->
        {
            try
            {
                savedZones = Map.copyOf(readZones(getPluginDirectory().join("shared-zones.tsv")));
            }
            catch (IOException e)
            {
                LOG.warn("Could not load Speaker Time's saved time zones", e);
            }
        });
    }

    @Override
    protected void shutDown()
    {
        toolbar.removeNavigation(navigation);
        clientThread.invoke(client::refreshChat);
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!PLAYER_CHAT.contains(event.getType()) || event.getType() == ChatMessageType.PRIVATECHATOUT
            || event.getMessage() == null)
        {
            return;
        }

        String value = announcementValue(event.getMessage());
        if (value == null)
        {
            return;
        }

        String speaker = playerKey(event.getName());
        if (speaker.isEmpty() || speaker.chars().anyMatch(Character::isISOControl))
        {
            return;
        }

        if ("off".equalsIgnoreCase(value))
        {
            executor.execute(() -> updateSavedZone(speaker, null, false));
            return;
        }

        ZoneId zone = parseZone(value);
        if (zone == null && event.getTimestamp() > 0)
        {
            zone = parseClockOffset(value, event.getTimestamp());
        }
        if (zone != null)
        {
            ZoneId sharedZone = zone;
            executor.execute(() -> updateSavedZone(speaker, sharedZone, false));
        }
    }

    void saveManualZone(String speaker, ZoneId zone, Consumer<Boolean> onSaved)
    {
        executor.execute(() ->
        {
            boolean saved = updateSavedZone(speaker, zone, true);
            SwingUtilities.invokeLater(() -> onSaved.accept(saved));
        });
    }

    private boolean updateSavedZone(String speaker, ZoneId zone, boolean manual)
    {
        try
        {
            Filepath directory = getPluginDirectory();
            directory.createDirectories();
            Filepath saved = directory.join("shared-zones.tsv");
            // A second RuneLite window can write to the same plugin data directory.
            try (FileChannel channel = directory.join("shared-zones.lock")
                .openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock())
            {
                Map<String, ZoneId> zones = readZones(saved);
                if (applyZone(zones, speaker, zone, manual))
                {
                    Filepath pending = directory.createTempFile("shared-zones-", ".tsv");
                    try
                    {
                        pending.write(encodeZones(zones));
                        pending.moveTo(saved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    }
                    finally
                    {
                        pending.deleteIfExists();
                    }
                }
                savedZones = Map.copyOf(zones);
                clientThread.invokeLater(client::refreshChat);
                return true;
            }
        }
        catch (IOException e)
        {
            LOG.warn("Could not update Speaker Time's saved time zones", e);
            return false;
        }
    }

    static boolean applyZone(Map<String, ZoneId> zones, String speaker, ZoneId zone, boolean manual)
    {
        // A leading tab distinguishes a manual entry from older shared-only rows.
        String key = manual ? '\t' + speaker : speaker;
        if (zone == null)
        {
            boolean removed = zones.remove(key) != null;
            if (!manual)
            {
                removed |= zones.remove('\t' + speaker) != null;
            }
            return removed;
        }

        if (zone.equals(zones.get(key)))
        {
            return false;
        }

        zones.remove(key);
        zones.put(key, zone);
        if (zones.size() > MAX_SPEAKERS)
        {
            zones.remove(zones.keySet().iterator().next());
        }
        return true;
    }

    static ZoneId effectiveZone(Map<String, ZoneId> zones, String speaker)
    {
        ZoneId shared = zones.get(speaker);
        return shared != null ? shared : zones.get('\t' + speaker);
    }

    private static Map<String, ZoneId> readZones(Filepath saved) throws IOException
    {
        if (!saved.isFile())
        {
            return new LinkedHashMap<>();
        }

        try (var reader = saved.openBufferedReader())
        {
            return decodeZones(reader.lines().limit(MAX_SPEAKERS));
        }
        catch (UncheckedIOException e)
        {
            throw e.getCause();
        }
    }

    static Map<String, ZoneId> decodeZones(Stream<String> lines)
    {
        Map<String, ZoneId> zones = new LinkedHashMap<>();
        lines.forEach(line ->
        {
            int separator = line.indexOf('\t', 1);
            if (separator <= 0)
            {
                return;
            }

            try
            {
                zones.put(line.substring(0, separator), ZoneId.of(line.substring(separator + 1)));
            }
            catch (DateTimeException ignored)
            {
                // An invalid entry does not prevent loading the remaining players.
            }
        });
        return zones;
    }

    static String encodeZones(Map<String, ZoneId> zones)
    {
        StringBuilder data = new StringBuilder();
        zones.forEach((name, zone) -> data.append(name).append('\t').append(zone.getId()).append('\n'));
        return data.toString();
    }

    @Subscribe(priority = -1)
    public void onScriptCallbackEvent(ScriptCallbackEvent event)
    {
        if (!"chatMessageBuilding".equals(event.getEventName()))
        {
            return;
        }

        int uid = client.getIntStack()[client.getIntStackSize() - 1];
        MessageNode message = client.getMessages().get(uid);
        if (message == null || !PLAYER_CHAT.contains(message.getType()) || message.getTimestamp() <= 0)
        {
            return;
        }

        Player local = client.getLocalPlayer();
        String speaker = playerKey(message.getName());
        boolean ownMessage = message.getType() == ChatMessageType.PRIVATECHATOUT
            || (local != null && !speaker.isEmpty() && speaker.equals(playerKey(local.getName())));
        ZoneId zone = ownMessage ? ZoneId.systemDefault() : effectiveZone(savedZones, speaker);
        if (zone != null)
        {
            client.getObjectStack()[client.getObjectStackSize() - 1] = formatTime(message.getTimestamp(), zone);
        }
    }

    private static Map<String, String> cityShortcuts()
    {
        Map<String, String> cities = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String zone : new TreeSet<>(ZONES.keySet()))
        {
            int separator = zone.lastIndexOf('/');
            if (separator < 0 || zone.startsWith("etc/"))
            {
                continue;
            }

            String city = shortcutKey(zone.substring(separator + 1));
            String existing = cities.putIfAbsent(city, zone);
            if (existing != null && !ZONES.get(existing).getRules().equals(ZONES.get(zone).getRules()))
            {
                ambiguous.add(city);
            }
        }

        cities.keySet().removeAll(ambiguous);
        try (InputStream data = SpeakerTimePlugin.class.getResourceAsStream("time-zone-shortcuts.tsv"))
        {
            if (data != null)
            {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(data, StandardCharsets.UTF_8)))
                {
                    reader.lines().forEach(line ->
                    {
                        String[] entry = line.split("\t", 2);
                        String zone = entry[0].toLowerCase(Locale.ROOT);
                        if (entry.length == 2 && ZONES.containsKey(zone))
                        {
                            for (String alias : entry[1].split("\\|"))
                            {
                                cities.put(shortcutKey(alias), zone);
                            }
                        }
                    });
                }
            }
        }
        catch (IOException | UncheckedIOException e)
        {
            LOG.warn("Could not load Speaker Time's extra time zone shortcuts", e);
        }
        return Map.copyOf(cities);
    }

    static String announcementValue(String message)
    {
        Matcher matcher = ANNOUNCEMENT.matcher(message.strip());
        return matcher.matches() ? matcher.group(1).strip() : null;
    }

    static ZoneId parseZone(String value)
    {
        String key = value.strip().replace(' ', '_').toLowerCase(Locale.ROOT);
        ZoneId zone = ZONES.get(key);
        return zone != null ? zone : ZONES.get(CITY_SHORTCUTS.getOrDefault(shortcutKey(value), key));
    }

    static List<String> timeZoneChoices()
    {
        Set<String> choices = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ZONES.values().forEach(zone -> choices.add(zone.getId()));
        CITY_SHORTCUTS.keySet().forEach(shortcut -> choices.add(shortcut.replace('_', ' ')));
        return List.copyOf(choices);
    }

    static String shortcutKey(String value)
    {
        String plain = SHORTCUT_ACCENTS.matcher(Normalizer.normalize(value.strip(), Normalizer.Form.NFD))
            .replaceAll("").toLowerCase(Locale.ROOT).replace(".", "").replace(",", "").replace("'", "").replace("’", "");
        return SHORTCUT_SEPARATORS.matcher(plain).replaceAll("_");
    }

    static ZoneOffset parseClockOffset(String value, long timestamp)
    {
        LocalTime reported;
        try
        {
            reported = LocalTime.parse(value, SHARED_CLOCK);
        }
        catch (DateTimeParseException ignored)
        {
            return null;
        }

        LocalTime utc = Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.UTC).toLocalTime();
        int difference = reported.toSecondOfDay() - utc.toSecondOfDay();
        // Current civil offsets are quarter-hour aligned. Snap to absorb typing and chat delays.
        int rounded = Math.floorDiv(difference + 450, 900) * 900;
        // Offsets one day apart display the same clock time.
        int wrapped = Math.floorMod(rounded + 43_200, 86_400) - 43_200;
        return ZoneOffset.ofTotalSeconds(wrapped);
    }

    static String formatTime(long timestamp, ZoneId zone)
    {
        return CLOCK.format(Instant.ofEpochSecond(timestamp).atZone(zone));
    }

    static String playerKey(String name)
    {
        return name == null ? "" : Text.removeTags(name).replace('\u00a0', ' ').strip().toLowerCase(Locale.ROOT);
    }
}
