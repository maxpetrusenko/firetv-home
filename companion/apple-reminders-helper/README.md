# apple-reminders-helper

Optional macOS helper for firetv-home. It reads Apple Reminders lists with EventKit and either writes a `reminders.json` file that firetv-home can read, or pushes open items into a Home Assistant todo entity, or both. firetv-home itself does not need it.

## Output schema (version 1)

```json
{
  "updated": "2026-01-01T12:00:00Z",
  "lists": [
    {
      "name": "Household",
      "items": [
        { "title": "Buy light bulbs", "due": "2026-01-02" },
        { "title": "Call the plumber", "due": "2026-01-03T09:00:00Z" },
        { "title": "Sort the garage", "due": null }
      ]
    }
  ]
}
```

- `updated`: ISO 8601 time the file was written. Informational.
- `lists[].name`: the Reminders list name. Shown as a heading on the TV.
- `items[].title`: the reminder text. Only incomplete reminders are written.
- `items[].due`: `YYYY-MM-DD` for a reminder with a date only, an ISO 8601 date-time for one with a time, or `null`. Items with a due date are shown first, earliest first.

## Requirements

- macOS 12 or later with the Xcode command line tools (`xcode-select --install`), for `swiftc`.
- Python 3 (only for `serve.py`).

## Install

1. Build the helper. This embeds an `Info.plist` so macOS can show the Reminders permission prompt.

        ./build.sh <path>/reminders-helper

2. Copy `config.example.json` to a private location, for example `<path>/config.json`, and edit it:

    - `lists`: Reminders list names to read. An empty list reads all lists.
    - `output`: where to write `reminders.json`. Omit it if you only push to Home Assistant.
    - `interval_seconds`: refresh period. `0` runs once and exits.
    - `ha_url`, `ha_token`, `ha_push`: optional Home Assistant push (see below). You can leave the credentials out of the file and set the `HA_URL` and `HA_TOKEN` environment variables instead.

    Every setting can also be given as a flag: `--config`, `--lists "A,B"`, `--output`, `--interval`.

3. Run it once from Terminal so macOS asks for Reminders access, and allow it:

        <path>/reminders-helper --config <path>/config.json

   If the prompt does not appear, or you denied it, open System Settings > Privacy & Security > Reminders and allow the helper. A launchd job started without a prior approval cannot show the prompt.

4. Check the output file:

        cat /path/to/reminders.json

## Serving the file to the TV

firetv-home reads the file over HTTP. `serve.py` serves exactly one path, `/reminders.json`, with no authentication:

    python3 serve.py --file <path>/reminders.json --bind <mac-lan-address> --port 8765

Bind it to the Mac's LAN address, not to `0.0.0.0` if you can avoid it, and do not forward the port to the internet. Then add this to the TV config:

    { "type": "apple_reminders", "url": "http://<mac-host>:8765/reminders.json" }

Alternatively copy the file to the TV with `adb push` into the app files directory and use `"file": "reminders.json"`.

## Home Assistant push

With `ha_push` set, each configured Reminders list is compared with a Home Assistant local todo entity (for example `todo.shopping_list`), and items that are not on the entity yet are added with the `todo.add_item` service. It never removes or completes items. Create the long-lived access token in your Home Assistant profile and keep it out of version control.

## Run at login with launchd

`launchd.plist.template` runs the helper and keeps it alive. `launchd-serve.plist.template` does the same for `serve.py`. Replace the `{{label}}`, `{{path}}` and `{{mac-lan-address}}` placeholders, then load it:

    sed -e 's|{{label}}|<label>|g' -e 's|{{path}}|<path>|g' launchd.plist.template > ~/Library/LaunchAgents/<label>.plist
    launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/<label>.plist

Stop it with `launchctl bootout gui/$(id -u)/<label>`. Rebuilding the helper changes its code signature, so macOS may ask for Reminders access again.
