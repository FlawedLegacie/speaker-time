package com.speakertime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

public class SpeakerTimeTest
{
    @Test
    public void manualZoneChoicesIncludeShortcutsAndFullZoneIds()
    {
        List<String> choices = SpeakerTimePlugin.timeZoneChoices();
        assertTrue(choices.contains("pacific time"));
        assertTrue(choices.contains("America/Vancouver"));
        for (String choice : choices)
        {
            assertNotNull(choice, SpeakerTimePlugin.parseZone(choice));
        }
    }

    @Test
    public void curatedShortcutsResolveToTheirListedTimeZoneRules() throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            SpeakerTimePlugin.class.getResourceAsStream("time-zone-shortcuts.tsv"), StandardCharsets.UTF_8)))
        {
            for (String line; (line = reader.readLine()) != null; )
            {
                String[] entry = line.split("\t", 2);
                ZoneId expected = ZoneId.of(entry[0]);
                for (String shortcut : entry[1].split("\\|"))
                {
                    ZoneId actual = SpeakerTimePlugin.parseZone(shortcut);
                    assertNotNull(shortcut, actual);
                    assertEquals(shortcut, expected.getRules(), actual.getRules());
                }
            }
        }
    }

    @Test
    public void acceptsAccentsAndPunctuationInShortcuts()
    {
        assertEquals(ZoneId.of("America/New_York"), SpeakerTimePlugin.parseZone("Washington, D.C."));
        assertEquals(ZoneId.of("America/Sao_Paulo"), SpeakerTimePlugin.parseZone("São Paulo"));
        assertEquals(ZoneId.of("America/Vancouver"), SpeakerTimePlugin.parseZone("Vancouver-BC"));
        assertEquals(ZoneId.of("Etc/GMT-7"), SpeakerTimePlugin.parseZone("Etc/GMT-7"));
    }

    @Test
    public void resolvesUsCoastsAndVancouverSeparately()
    {
        assertEquals(ZoneId.of("America/New_York"), SpeakerTimePlugin.parseZone("East Coast"));
        assertEquals(ZoneId.of("America/Los_Angeles"), SpeakerTimePlugin.parseZone(
            SpeakerTimePlugin.announcementValue("!tz WEST COAST")));
        assertEquals(ZoneId.of("America/Los_Angeles"), SpeakerTimePlugin.parseZone("Pacific Time"));
        assertEquals(ZoneId.of("America/Los_Angeles"), SpeakerTimePlugin.parseZone(
            SpeakerTimePlugin.announcementValue("!tz Pacific Time")));
        assertEquals(ZoneId.of("America/Vancouver"), SpeakerTimePlugin.parseZone("Vancouver"));
    }

    @Test
    public void sharedZoneOverridesSavedManualZoneAndOptOutClearsBoth()
    {
        String player = "goldzodia";
        ZoneId manual = ZoneId.of("Europe/London");
        ZoneId shared = ZoneId.of("America/Los_Angeles");
        Map<String, ZoneId> zones = new LinkedHashMap<>();

        SpeakerTimePlugin.applyZone(zones, player, manual, true);
        zones = SpeakerTimePlugin.decodeZones(SpeakerTimePlugin.encodeZones(zones).lines());
        assertEquals(manual, SpeakerTimePlugin.effectiveZone(zones, player));

        SpeakerTimePlugin.applyZone(zones, player, shared, false);
        zones = SpeakerTimePlugin.decodeZones(SpeakerTimePlugin.encodeZones(zones).lines());
        assertEquals(shared, SpeakerTimePlugin.effectiveZone(zones, player));

        SpeakerTimePlugin.applyZone(zones, player, null, false);
        assertEquals(Map.of(), SpeakerTimePlugin.decodeZones(SpeakerTimePlugin.encodeZones(zones).lines()));
    }

    @Test
    public void manualRemovalPreservesThePlayersShare()
    {
        Map<String, ZoneId> zones = SpeakerTimePlugin.decodeZones("goldzodia\tEurope/London".lines());
        SpeakerTimePlugin.applyZone(zones, "goldzodia", ZoneId.of("America/Los_Angeles"), true);
        SpeakerTimePlugin.applyZone(zones, "goldzodia", null, true);

        assertEquals(Map.of("goldzodia", ZoneId.of("Europe/London")), zones);
    }

    @Test
    public void savedZonesRoundTripAndRemovedEntriesStayCleared()
    {
        ZoneId london = ZoneId.of("Europe/London");
        ZoneOffset offset = ZoneOffset.ofHoursMinutes(5, 45);
        String saved = SpeakerTimePlugin.encodeZones(Map.of("gamer xy", london, "player b", offset));

        Map<String, ZoneId> restored = SpeakerTimePlugin.decodeZones(saved.lines());
        assertEquals(london, restored.get("gamer xy"));
        assertEquals(offset, restored.remove("player b"));

        saved = SpeakerTimePlugin.encodeZones(restored);
        assertEquals(Map.of("gamer xy", london),
            SpeakerTimePlugin.decodeZones((saved + "bad entry\tNot/AZone\n").lines()));
    }

    @Test
    public void acceptsClockAnnouncementsWithOrWithoutCommandColon()
    {
        assertEquals("1:40 pm", SpeakerTimePlugin.announcementValue("!Tz 1:40 pm"));
        assertEquals("1:40 pm", SpeakerTimePlugin.announcementValue("!tz: 1:40 pm"));
        assertEquals("LA", SpeakerTimePlugin.announcementValue("!tz:LA"));
        assertNull(SpeakerTimePlugin.announcementValue("!tzwhatever"));
    }

    @Test
    public void infersClockOffsetWithTypingDelay()
    {
        long timestamp = Instant.parse("2026-11-08T21:01:12Z").getEpochSecond();

        assertEquals("[1:05 PM]", SpeakerTimePlugin.formatTime(timestamp + 228,
            SpeakerTimePlugin.parseClockOffset("1:00 pm", timestamp)));
        assertNull(SpeakerTimePlugin.parseClockOffset("13:00 PM", timestamp));
        assertNull(SpeakerTimePlugin.parseClockOffset("1:00", timestamp));
    }

    @Test
    public void infersQuarterHourOffsetAcrossMidnight()
    {
        long timestamp = Instant.parse("2026-11-08T23:30:00Z").getEpochSecond();

        assertEquals("[5:15 AM]", SpeakerTimePlugin.formatTime(timestamp,
            SpeakerTimePlugin.parseClockOffset("5:15 AM", timestamp)));
    }

    @Test
    public void resolvesCityShortcuts()
    {
        assertEquals(SpeakerTimePlugin.parseZone("America/Los_Angeles"), SpeakerTimePlugin.parseZone("LA"));
        assertEquals(SpeakerTimePlugin.parseZone("America/Los_Angeles"), SpeakerTimePlugin.parseZone("Los Angeles"));
        assertEquals(SpeakerTimePlugin.parseZone("Europe/London"), SpeakerTimePlugin.parseZone("london"));
        assertEquals(SpeakerTimePlugin.parseZone("America/New_York"), SpeakerTimePlugin.parseZone("New York"));
        assertEquals(SpeakerTimePlugin.parseZone("Asia/Tokyo"), SpeakerTimePlugin.parseZone("Osaka"));
        assertEquals(SpeakerTimePlugin.parseZone("America/Argentina/Buenos_Aires"),
            SpeakerTimePlugin.parseZone("Buenos Aires"));
        assertNull(SpeakerTimePlugin.parseZone("CST"));
    }

    @Test
    public void formatsTheSameMessageInEachSpeakersZone()
    {
        long timestamp = Instant.parse("2026-11-08T21:00:00Z").getEpochSecond();

        assertEquals("[9:00 PM]", SpeakerTimePlugin.formatTime(timestamp,
            SpeakerTimePlugin.parseZone("Europe/London")));
        assertEquals("[1:00 PM]", SpeakerTimePlugin.formatTime(timestamp,
            SpeakerTimePlugin.parseZone("america/los angeles")));
    }

    @Test
    public void usesDaylightSavingRulesAtTheMessageTimestamp()
    {
        long timestamp = Instant.parse("2026-07-01T21:00:00Z").getEpochSecond();

        assertEquals("[2:00 PM]", SpeakerTimePlugin.formatTime(timestamp,
            SpeakerTimePlugin.parseZone("America/Los_Angeles")));
        assertNull(SpeakerTimePlugin.parseZone("not/a-zone"));
    }
}
