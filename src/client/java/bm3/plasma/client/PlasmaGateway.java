package bm3.plasma.client;

import bm3.plasma.LocalBridge;
import bm3.plasma.PendingRequest;
import bm3.plasma.PlasmaMod;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PlasmaGateway implements LocalBridge.BridgeListener {
	private enum ActionKind { ALLOW, ALLOW_ALWAYS, DENY, DENY_ALWAYS, TRUST, BETRAY, CLOSE }

	private record StagedAction(ActionKind kind, PendingRequest request) {
	}

	private final LocalBridge bridge;
	private final Minecraft mc = Minecraft.getInstance();
	private final ConcurrentLinkedQueue<Component> pendingChat = new ConcurrentLinkedQueue<>();
	private final Deque<PendingRequest> pendingRequests = new ArrayDeque<>();
	private final Set<String> trustedIps = ConcurrentHashMap.newKeySet();
	private final Set<String> blockedIps = ConcurrentHashMap.newKeySet();

	private volatile StagedAction staged;
	private volatile boolean echo = true;

	public PlasmaGateway(LocalBridge bridge) {
		this.bridge = bridge;
		registerCommands();
		ClientTickEvents.END_CLIENT_TICK.register(client -> flushChat());
	}

	public void ready() {
		chat(tr("plasma.banner.warning").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		chat(tr("plasma.banner.title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		chat(tr("plasma.banner.body").withStyle(ChatFormatting.GOLD));
		chat(tr("plasma.banner.liability").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		chat(tr("plasma.banner.caution").withStyle(ChatFormatting.GOLD));
		chat(message(tr("plasma.ready.closed", command("/plasma agree")).withStyle(ChatFormatting.WHITE)));
	}

	@Override
	public void onCodeRequest(PendingRequest request) {
		String ip = request.getSourceIp();
		if (trustedIps.contains(ip)) {
			chat(message(tr("plasma.auto.trusted",
				styled(ip, ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC),
				code(request.describe())).withStyle(ChatFormatting.GREEN)));
			request.execute();
			return;
		}
		if (blockedIps.contains(ip)) {
			chat(message(tr("plasma.auto.blocked",
				styled(ip, ChatFormatting.DARK_RED, ChatFormatting.ITALIC),
				code(request.describe())).withStyle(ChatFormatting.RED)));
			request.deny("BLOCKED_IP");
			return;
		}

		synchronized (pendingRequests) {
			pendingRequests.addLast(request);
		}
		chat(message(tr("plasma.request.from",
			styled(request.getSource(), ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
			code(request.describe())).withStyle(ChatFormatting.GRAY)));
		chat(message(tr("plasma.request.commands",
			command("/plasma allow"),
			command("/plasma deny"),
			styled("always", ChatFormatting.AQUA, ChatFormatting.ITALIC),
			command("/plasma trust"),
			command("/plasma betray")).withStyle(ChatFormatting.GRAY)));
	}

	@Override
	public void onExecuted(PendingRequest request, String output) {
		chat(message(tr("plasma.executed", code(request.describe())).withStyle(ChatFormatting.GREEN)));
		if (output != null && !output.isBlank()) {
			for (String line : output.split("\\R", -1)) {
				chatAlways(message(styled("    " + line, ChatFormatting.GRAY)));
			}
		}
	}

	@Override
	public void onDenied(PendingRequest request, String reason) {
		chat(message(tr("plasma.denied",
			code(request.describe()),
			styled(reason, ChatFormatting.DARK_RED)).withStyle(ChatFormatting.RED)));
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
			ClientCommands.literal("plasma")
				.then(ClientCommands.literal("allow")
					.executes(context -> {
						stageAllow(false);
						return 1;
					})
					.then(ClientCommands.literal("always").executes(context -> {
						stageAllow(true);
						return 1;
					})))
				.then(ClientCommands.literal("deny")
					.executes(context -> {
						stageDeny(false);
						return 1;
					})
					.then(ClientCommands.literal("always").executes(context -> {
						stageDeny(true);
						return 1;
					})))
				.then(ClientCommands.literal("agree").executes(context -> {
					agree();
					return 1;
				}))
				.then(ClientCommands.literal("confirm").executes(context -> {
					confirm();
					return 1;
				}))
				.then(ClientCommands.literal("trust").executes(context -> {
					stageTrust();
					return 1;
				}))
				.then(ClientCommands.literal("betray").executes(context -> {
					stageBetray();
					return 1;
				}))
				.then(ClientCommands.literal("forcekill").executes(context -> {
					forceKill();
					return 1;
				}))
				.then(ClientCommands.literal("echo")
					.then(ClientCommands.literal("true").executes(context -> {
						setEcho(true);
						return 1;
					}))
					.then(ClientCommands.literal("false").executes(context -> {
						setEcho(false);
						return 1;
					})))
				.then(ClientCommands.literal("close").executes(context -> {
					stageClose();
					return 1;
				}))
		));
	}

	private PendingRequest currentRequest() {
		synchronized (pendingRequests) {
			while (!pendingRequests.isEmpty()) {
				PendingRequest head = pendingRequests.peek();
				if (head.isResolved()) {
					pendingRequests.poll();
					continue;
				}
				return head;
			}
			return null;
		}
	}

	private void stageAllow(boolean always) {
		PendingRequest request = currentRequest();
		if (request == null) {
			feedback(message(tr("plasma.no.pending").withStyle(ChatFormatting.RED)));
			return;
		}
		staged = new StagedAction(always ? ActionKind.ALLOW_ALWAYS : ActionKind.ALLOW, request);
		MutableComponent action = always
			? tr("plasma.stage.allow.always",
				styled(request.getSourceIp(), ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC),
				code(request.describe())).withStyle(ChatFormatting.YELLOW)
			: tr("plasma.stage.allow", code(request.describe())).withStyle(ChatFormatting.YELLOW);
		feedback(message(action.append(styled(" ", ChatFormatting.WHITE)).append(confirmTail())));
	}

	private void stageDeny(boolean always) {
		PendingRequest request = currentRequest();
		if (request == null) {
			feedback(message(tr("plasma.no.pending").withStyle(ChatFormatting.RED)));
			return;
		}
		staged = new StagedAction(always ? ActionKind.DENY_ALWAYS : ActionKind.DENY, request);
		MutableComponent action = always
			? tr("plasma.stage.deny.always",
				styled(request.getSourceIp(), ChatFormatting.DARK_RED, ChatFormatting.ITALIC),
				code(request.describe())).withStyle(ChatFormatting.RED)
			: tr("plasma.stage.deny", code(request.describe())).withStyle(ChatFormatting.RED);
		feedback(message(action.append(styled(" ", ChatFormatting.WHITE)).append(confirmTail())));
	}

	private void stageTrust() {
		PendingRequest request = currentRequest();
		if (request == null) {
			feedback(message(tr("plasma.no.pending").withStyle(ChatFormatting.RED)));
			return;
		}
		staged = new StagedAction(ActionKind.TRUST, request);
		feedback(message(tr("plasma.stage.trust",
			styled(request.getSourceIp(), ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC),
			command("/plasma betray")).withStyle(ChatFormatting.YELLOW)
			.append(styled(" ", ChatFormatting.WHITE))
			.append(confirmTail())));
	}

	private void stageBetray() {
		PendingRequest request = currentRequest();
		if (request == null) {
			feedback(message(tr("plasma.no.pending").withStyle(ChatFormatting.RED)));
			return;
		}
		staged = new StagedAction(ActionKind.BETRAY, request);
		feedback(message(tr("plasma.stage.betray",
			styled(request.getSourceIp(), ChatFormatting.DARK_RED, ChatFormatting.ITALIC)).withStyle(ChatFormatting.RED)
			.append(styled(" ", ChatFormatting.WHITE))
			.append(confirmTail())));
	}

	private void stageClose() {
		staged = new StagedAction(ActionKind.CLOSE, null);
		feedback(message(tr("plasma.stage.close").withStyle(ChatFormatting.YELLOW)
			.append(styled(" ", ChatFormatting.WHITE))
			.append(confirmTail())));
	}

	private void agree() {
		if (bridge.isRunning()) {
			feedback(message(tr("plasma.agree.already",
				styled(String.valueOf(bridge.getPort()), ChatFormatting.DARK_GREEN, ChatFormatting.BOLD),
				command("/plasma close")).withStyle(ChatFormatting.GREEN)));
			return;
		}
		try {
			bridge.start();
			feedback(message(tr("plasma.agree.open",
				styled(String.valueOf(bridge.getPort()), ChatFormatting.DARK_GREEN, ChatFormatting.BOLD),
				command("/plasma close")).withStyle(ChatFormatting.GREEN)));
		} catch (IOException e) {
			feedback(message(tr("plasma.agree.failed",
				styled(e.getMessage(), ChatFormatting.DARK_RED, ChatFormatting.ITALIC)).withStyle(ChatFormatting.RED)));
		}
	}

	private void forceKill() {
		staged = null;
		synchronized (pendingRequests) {
			while (!pendingRequests.isEmpty()) {
				PendingRequest request = pendingRequests.poll();
				if (!request.isResolved()) {
					request.deny("FORCEKILL");
				}
			}
		}
		try {
			bridge.close();
		} catch (IOException ignored) {
		}
		feedback(message(tr("plasma.forcekill").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
	}

	private void confirm() {
		StagedAction action = staged;
		staged = null;
		if (action == null) {
			feedback(message(tr("plasma.nothing.confirm").withStyle(ChatFormatting.RED)));
			return;
		}
		switch (action.kind()) {
			case ALLOW -> {
				if (action.request() != null) {
					action.request().execute();
				}
			}
			case ALLOW_ALWAYS -> {
				if (action.request() != null) {
					trustedIps.add(action.request().getSourceIp());
					action.request().execute();
				}
			}
			case DENY -> {
				if (action.request() != null) {
					action.request().deny("DENIED_BY_USER");
				}
			}
			case DENY_ALWAYS -> {
				if (action.request() != null) {
					blockedIps.add(action.request().getSourceIp());
					action.request().deny("DENIED_ALWAYS");
				}
			}
			case TRUST -> {
				if (action.request() != null) {
					trustedIps.add(action.request().getSourceIp());
					action.request().execute();
				}
			}
			case BETRAY -> {
				if (action.request() != null) {
					String ip = action.request().getSourceIp();
					trustedIps.remove(ip);
					blockedIps.remove(ip);
					action.request().deny("BETRAYED");
				}
			}
			case CLOSE -> {
				try {
					bridge.close();
				} catch (Exception ignored) {
				}
				chat(message(tr("plasma.bridge.closed").withStyle(ChatFormatting.RED)));
			}
		}
	}

	private static MutableComponent styled(String text, ChatFormatting... formats) {
		return Component.literal(text).withStyle(formats);
	}

	private static MutableComponent tr(String key, Object... args) {
		return Component.translatable(key, args);
	}

	private static MutableComponent prefix() {
		return Component.literal("Plasma: ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
	}

	private static MutableComponent message(Component content) {
		return prefix().append(content);
	}

	private static MutableComponent command(String cmd) {
		return Component.literal(cmd).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD, ChatFormatting.ITALIC);
	}

	private static MutableComponent code(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC);
	}

	private static MutableComponent confirmTail() {
		return tr("plasma.prompt.confirm", command("/plasma confirm")).withStyle(ChatFormatting.WHITE);
	}

	private void setEcho(boolean value) {
		echo = value;
		feedbackAlways(message(tr(value ? "plasma.echo.on" : "plasma.echo.off")
			.withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED)));
	}

	private void chat(Component message) {
		if (echo) {
			pendingChat.add(message);
		}
	}

	private void chatAlways(Component message) {
		pendingChat.add(message);
	}

	private void feedback(Component message) {
		chat(message);
		flushChat();
	}

	private void feedbackAlways(Component message) {
		chatAlways(message);
		flushChat();
	}

	private void flushChat() {
		if (mc.player == null) {
			return;
		}
		Component message;
		while ((message = pendingChat.poll()) != null) {
			mc.player.sendSystemMessage(message);
		}
	}
}
