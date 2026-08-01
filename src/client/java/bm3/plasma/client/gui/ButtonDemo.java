package bm3.plasma.client.gui;

/**
 * Payload target ({@code {"className":"bm3.plasma.client.gui.ButtonDemo","method":"run"}})
 * that opens a screen with a clickable button pinned to the top-left corner (0,0).
 */
public final class ButtonDemo implements Runnable {
	@Override
	public void run() {
		SimpleGui.builder("Plasma demo")
			.button(0, 0, 100, 20, "Click me", () -> {
				System.out.println("Button at (0,0) clicked");
				SimpleGui.close();
			})
			.show();
	}
}
