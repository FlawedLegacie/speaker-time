# Speaker Time for RuneLite

Speaker Time changes the chat timestamp to the speaker's local time when their time zone is known. The text of ordinary chat messages stays the same. Sharing is opt-in. A player sends a time or time zone in chat, and only clients running Speaker Time that receive the message can use it.

## Try it with a friend

1. Run the development client with `./gradlew run` (`gradlew.bat run` on Windows). For a Jagex account, follow [RuneLite's development-client login instructions](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).
2. Enable **Speaker Time** in RuneLite's plugin settings. You can also enable RuneLite's **Chat Timestamps** to show your own local time on lines from players who haven't shared a zone.
3. Have your friend manually send `!tz: LA` in a private message or another chat you both see. The sender can use the regular game client. The colon after `!tz` is optional, so `!tz LA` works too. `!tz: London` is another example. Zone names are case-insensitive and spaces can replace underscores.
4. Have them send an ordinary message. Verify that the time next to their name matches their local time. Send a message yourself and verify that your own timestamp uses your computer's time zone.
5. Have them send `!tz: off`. Their later messages should return to your regular chat display. Repeat with a different time zone and check that it takes effect.

Sharing goes through the game chat channel the player chooses. The setup message remains visible to recipients like any other chat message. The plugin sends no messages or data on its own. Shared settings survive client restarts and plugin toggles. Each recipient needs to receive the original announcement, and `!tz off` clears the saved setting for each recipient who sees it. A valid zone update or opt-out may also affect older lines when RuneLite redraws them.

## Manual time zones

Click the **Speaker Time** chat-bubble icon in RuneLite's sidebar. Enter the player's RuneScape name and a city or time zone, such as `London` or `America/Los_Angeles`, then click **Save manual zone**. The entry stays on this device after restarting RuneLite. You can also browse the time-zone list below the buttons. Type in the city or time-zone field to filter the list, then select an item to fill the field.

A player's own `!tz` share takes priority. If they send `!tz off` in a chat you receive, Speaker Time clears both settings for that player. To remove just your manual entry, enter their name and click **Remove manual zone**.

## City shortcuts

Try `!tz: LA`, `!tz: New York`, `!tz: London`, `!tz: Paris`, `!tz: Tokyo`, or `!tz: Sydney`. Most cities listed in the Java time zone database work by city name alone. Common aliases such as `NYC`, `SF`, `Miami`, `Mumbai`, and `Beijing` also work. If your city is missing or ambiguous, use the full time zone ID, such as `America/Los_Angeles`.

`East Coast` uses U.S. Eastern Time (New York), and `West Coast` and `Pacific Time` both use U.S. Pacific Time (Los Angeles). All three work with `!tz` and in the manual time zone form. Use `Vancouver` for Vancouver, B.C., which has its own time zone rules.

You can also use regional names and local spellings such as `UK`, `Bengaluru`, `Gold Coast`, `München`, or `Vancouver BC`. Shortcuts ignore capitalization, accents, and common punctuation. The [extra shortcuts list](src/main/resources/com/speakertime/time-zone-shortcuts.tsv) shows the zone each shortcut uses. For places spanning several time zones, use a city or a full time zone ID.

## Share a clock reading

Send `!tz: 1:00 PM` with your current local time, including AM or PM. Speaker Time estimates the offset from the chat message timestamp and rounds it to the nearest 15 minutes to account for small delays. This saves a fixed offset until it is updated or cleared. Send an updated reading after your clocks change, or use a city shortcut to follow daylight saving time automatically. Use `!tz: off` to remove either kind of setting.

## Privacy

Speaker Time uses the chat sender's display name and message timestamp to apply the setting that sender shares. It saves only normalized display names and their shared or manually set time zone IDs or UTC offsets in the local RuneLite plugin data directory. It makes no network requests and saves no chat messages. The file is separate from RuneLite account settings and does not sync across devices. At most 2,048 shared and manual entries are kept in total. When the limit is reached, a new entry replaces the oldest saved entry. Use a private message when you want to share with one person.

## Build

Use Java 17 to run Gradle. The plugin targets Java 11. Run `./gradlew test jar` (`gradlew.bat test jar` on Windows). The jar in `build/libs` is for development and review. Normal installation from RuneLite requires Plugin Hub submission and approval.
