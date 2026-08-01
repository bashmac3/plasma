# Simple GUI payloads

These payloads open small client-side screens using the `bm3.plasma.client.gui` package
that ships inside the mod (`SimpleGui` builder, plus the `ButtonDemo` and `WidgetGalleryDemo`
Runnable targets).

## How to send

```sh
# from the repo root (token is auto-discovered)
python3 scripts/send_payload.py 127.0.0.1 <port> "" "$(cat examples/gui/01_button_at_origin.json)"

# or pass the JSON as the 4th positional arg, e.g.:
python3 scripts/send_payload.py 127.0.0.1 46946 "" '{"className":"bm3.plasma.client.gui.ButtonDemo","method":"run"}'
```

## The payloads

| File | What it opens |
|---|---|
| `01_button_at_origin.json` | A screen with a clickable button pinned to the top-left corner `(0, 0)`. |
| `02_widget_gallery.json` | A screen with a label, an edit box, a checkbox, a cycle button, and a submit button that echoes the values. |
| `03_snippet_screen.json` | The same builder driven from an inline `code` snippet instead of a Runnable class. |

## The builder API

From any payload snippet:

```java
bm3.plasma.client.gui.SimpleGui.builder("Title")
    .button(x, y, w, h, "Label", () -> { ... })        // click handler
    .label(x, y, "Text")                                // plain text
    .editBox("name", x, y, w, h, "Hint", "initial")     // text input; read via builder.text("name")
    .checkbox("flag", x, y, "Label", false, v -> { })   // read via builder.checked("flag")
    .cycle("mode", x, y, w, h, "Label", new String[]{"a","b"}, 0, i -> { }) // read via builder.selected("mode")
    .show();
```

- Positions are window/screen coordinates; `(0, 0)` is the top-left corner.
- All callbacks run on the render thread, so it is safe to touch the game state inside them.
- Widget values are read back with `builder.text(name)`, `builder.checked(name)` and
  `builder.selected(name)` from inside a callback (e.g. a submit button).
- `bm3.plasma.client.gui.SimpleGui.close()` closes the current screen.

## Notes

- In `01` and `02` the payload uses the `className` form, so the mod ships the target
  classes (`bm3.plasma.client.gui.ButtonDemo`, `bm3.plasma.client.gui.WidgetGalleryDemo`)
  and the bridge loads them with `Class.forName`.
- In `03` the `code` snippet references the mod's own class directly, which requires the
  mod classes to be on the compile classpath (true in a dev environment / when the mod
  jar is on the classpath).
