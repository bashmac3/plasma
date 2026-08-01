package bm3.plasma.client.gui;

/**
 * Payload target ({@code {"className":"bm3.plasma.client.gui.WidgetGalleryDemo","method":"run"}})
 * that opens a screen showing the widget types supported by {@link SimpleGui}.
 */
public final class WidgetGalleryDemo implements Runnable {
	@Override
	public void run() {
		SimpleGui.Builder builder = SimpleGui.builder("Widget gallery");
		builder
			.label(8, 8, "A label")
			.editBox("name", 8, 24, 120, 20, "Enter a name", "Alice")
			.checkbox("ready", 8, 52, "Ready", false, v -> System.out.println("Ready=" + v))
			.cycle("mode", 8, 80, 120, 20, "Mode", new String[]{"easy", "normal", "hard"}, 1,
				i -> System.out.println("Mode=" + i))
			.button(8, 112, 120, 20, "Submit", () -> {
				System.out.println("name=" + builder.text("name"));
				System.out.println("ready=" + builder.checked("ready"));
				System.out.println("mode=" + builder.selected("mode"));
				SimpleGui.close();
			})
			.show();
	}
}
