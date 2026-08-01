package bm3.plasma.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Small fluent builder for creating simple client-side screens from payload code.
 *
 * <pre>{@code
 * SimpleGui.builder("My screen")
 *     .button(0, 0, 100, 20, "Click me", () -> System.out.println("clicked"))
 *     .editBox("name", 0, 30, 100, 20, "Name", "")
 *     .checkbox("ready", 0, 60, "Ready", false, v -> {})
 *     .cycle("mode", 0, 90, 100, 20, "Mode", new String[]{"a", "b", "c"}, 0, i -> {})
 *     .show();
 * }</pre>
 *
 * <p>Widget callbacks run on the render thread. Named widgets can be read back with
 * {@link Builder#text(String)}, {@link Builder#checked(String)} and
 * {@link Builder#selected(String)} once the screen is open.
 */
public final class SimpleGui {
	private SimpleGui() {
	}

	public static Builder builder(String title) {
		return new Builder(Component.literal(title));
	}

	public static void close() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.setScreen(null));
	}

	public static final class Builder {
		private final Component title;
		private final List<Supplier<AbstractWidget>> factories = new ArrayList<>();
		private final Map<String, AbstractWidget> named = new HashMap<>();

		private Builder(Component title) {
			this.title = title;
		}

		public Builder button(int x, int y, int width, int height, String label, Runnable onClick) {
			factories.add(() -> Button.builder(Component.literal(label), b -> onClick.run())
				.bounds(x, y, width, height)
				.build());
			return this;
		}

		public Builder label(int x, int y, String text) {
			factories.add(() -> {
				Font font = Minecraft.getInstance().font;
				return new StringWidget(x, y, font.width(text), font.lineHeight, Component.literal(text), font);
			});
			return this;
		}

		public Builder editBox(String name, int x, int y, int width, int height, String hint, String value) {
			factories.add(() -> {
				EditBox box = new EditBox(Minecraft.getInstance().font, x, y, width, height, Component.literal(hint));
				box.setValue(value);
				named.put(name, box);
				return box;
			});
			return this;
		}

		public Builder checkbox(String name, int x, int y, String label, boolean selected, Consumer<Boolean> onChange) {
			factories.add(() -> {
				Checkbox box = Checkbox.builder(Component.literal(label), Minecraft.getInstance().font)
					.pos(x, y)
					.selected(selected)
					.onValueChange((widget, value) -> onChange.accept(value))
					.build();
				named.put(name, box);
				return box;
			});
			return this;
		}

		public Builder cycle(String name, int x, int y, int width, int height, String label,
				String[] options, int selected, Consumer<Integer> onChange) {
			factories.add(() -> {
				List<String> values = Arrays.asList(options);
				CycleButton<String> cycle = CycleButton.<String>builder(Component::literal, options[selected])
					.withValues(values)
					.create(x, y, width, height, Component.literal(label),
						(button, value) -> onChange.accept(values.indexOf(value)));
				named.put(name, cycle);
				return cycle;
			});
			return this;
		}

		public void show() {
			List<Supplier<AbstractWidget>> snapshot = new ArrayList<>(factories);
			Screen screen = new Screen(title) {
				@Override
				protected void init() {
					for (Supplier<AbstractWidget> factory : snapshot) {
						addRenderableWidget(factory.get());
					}
				}
			};
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> mc.gui.setScreen(screen));
		}

		public String text(String name) {
			AbstractWidget widget = named.get(name);
			return widget instanceof EditBox ? ((EditBox) widget).getValue() : null;
		}

		public boolean checked(String name) {
			AbstractWidget widget = named.get(name);
			return widget instanceof Checkbox && ((Checkbox) widget).selected();
		}

		public String selected(String name) {
			AbstractWidget widget = named.get(name);
			if (widget instanceof CycleButton<?> cycle) {
				Object value = cycle.getValue();
				return value == null ? null : value.toString();
			}
			return null;
		}
	}
}
