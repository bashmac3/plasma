# Simple GUI payloads

These payloads build small client-side screens using `bm3.plasma.client.gui.SimpleGui`,
the fluent builder that ships inside the mod. Everything here is snippet-driven: no
compiled demo classes, no stale one-off methods. You edit the snippet and re-send it.

## Quick start (hot-reload loop)

The fastest way to iterate on a screen is to edit a payload file while the game runs.
The bridge compiles snippets at runtime, so a save-and-resend gives you the updated
screen immediately — no mod rebuild, no reload.

```sh
# from the repo root (token is auto-discovered; pass a token as arg 3 if needed)
python3 scripts/send_payload.py 127.0.0.1 <port> --watch examples/gui/dev_screen.json
```

- Edit `examples/gui/dev_screen.json`, save, and the screen updates in-game.
- Ctrl+C to stop watching.

Or send a single payload once:

```sh
python3 scripts/send_payload.py 127.0.0.1 <port> "" "$(cat examples/gui/01_button_at_origin.json)"
```

## The payloads

| File | What it opens |
|---|---|
| `01_button_at_origin.json` | A screen with a clickable button pinned to the top-left corner `(0, 0)`. |
| `02_widget_gallery.json` | A screen with a label, an edit box, a checkbox, a cycle button, and a submit button that echoes the values. |
| `dev_screen.json` | Scratch screen for hot-reload development. Edit and save to re-render. |

## The builder API

From any payload snippet:

```java
bm3.plasma.client.gui.SimpleGui.Builder b = bm3.plasma.client.gui.SimpleGui.builder("Title");
b.button(x, y, w, h, "Label", () -> { ... })        // click handler
 .label(x, y, "Text")                                // plain text
 .editBox("name", x, y, w, h, "Hint", "initial")     // text input; read via b.text("name")
 .checkbox("flag", x, y, "Label", false, v -> { })   // read via b.checked("flag")
 .cycle("mode", x, y, w, h, "Label", new String[]{"a","b"}, 0, i -> { }) // read via b.selected("mode")
 .show();
```

- Positions are window/screen coordinates; `(0, 0)` is the top-left corner.
- All callbacks run on the render thread, so it is safe to touch the game state inside them.
- Widget values are read back with `b.text(name)`, `b.checked(name)` and `b.selected(name)`
  from inside a callback (e.g. a submit button).
- `bm3.plasma.client.gui.SimpleGui.close()` closes the current screen.

## Notes

- Snippets reference the mod's own `SimpleGui` class directly, which requires the mod
  classes to be on the compile classpath. This is true in a dev environment
  (`./gradlew runClient`), so use that for UI development.
- The payload is a single JSON object with `{"method":"run","code":"..."}`; the `code`
  is compiled in-memory by the bridge each time it is sent.
