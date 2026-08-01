package bm3.plasma.client;

import bm3.plasma.LocalBridge;
import bm3.plasma.LocalBridgeConfig;
import bm3.plasma.PendingRequest;
import bm3.plasma.ProfileStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PlasmaGateway implements LocalBridge.BridgeListener {
	private enum ActionKind { ALLOW, ALLOW_ALWAYS, DENY, DENY_ALWAYS, TRUST, BETRAY, CLOSE }

	private record StagedAction(ActionKind kind, PendingRequest request) {
	}

	private final LocalBridge bridge;
	private final Path configDir;
	private final Minecraft mc = Minecraft.getInstance();
	private final ConcurrentLinkedQueue<Component> pendingChat = new ConcurrentLinkedQueue<>();
	private final Deque<PendingRequest> pendingRequests = new ArrayDeque<>();
	private final Set<String> trustedIps = ConcurrentHashMap.newKeySet();
	private final Set<String> blockedIps = ConcurrentHashMap.newKeySet();
	private final Set<String> blessedHashes = ConcurrentHashMap.newKeySet();
	private final ProfileStore profiles;

	private volatile StagedAction staged;
	private volatile boolean echo = true;

	public PlasmaGateway(LocalBridge bridge, Path configDir) {
		this.bridge = bridge;
		this.configDir = configDir;
		this.profiles = new ProfileStore(configDir);
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
		if (blockedIps.contains(ip)) {
			chat(message(tr("plasma.auto.blocked",
				styled(ip, ChatFormatting.DARK_RED, ChatFormatting.ITALIC),
				code(request.describe())).withStyle(ChatFormatting.RED)));
			request.deny("BLOCKED_IP");
			return;
		}
		if (trustedIps.contains(ip)) {
			chat(message(tr("plasma.auto.trusted",
				styled(ip, ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC),
				code(request.describe())).withStyle(ChatFormatting.GREEN)));
			request.execute();
			return;
		}
		if (blessedHashes.contains(request.getPayloadHash())) {
			chat(message(tr("plasma.auto.blessed",
				code(request.describe())).withStyle(ChatFormatting.GREEN)));
			request.execute();
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
			command("/plasma betray"),
			command("/plasma bless")).withStyle(ChatFormatting.GRAY)));
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
				.then(ClientCommands.literal("block")
					.then(ClientCommands.argument("ip", StringArgumentType.greedyString()).executes(context -> {
						blockIp(StringArgumentType.getString(context, "ip"));
						return 1;
					})))
				.then(ClientCommands.literal("unblock")
					.then(ClientCommands.argument("ip", StringArgumentType.greedyString()).executes(context -> {
						unblockIp(StringArgumentType.getString(context, "ip"));
						return 1;
					})))
				.then(ClientCommands.literal("unlock").executes(context -> {
					unlock();
					return 1;
				}))
				.then(ClientCommands.literal("bless").executes(context -> {
					stageBless();
					return 1;
				}))
				.then(ClientCommands.literal("unbless")
					.then(ClientCommands.argument("hash", StringArgumentType.greedyString()).executes(context -> {
						unbless(StringArgumentType.getString(context, "hash"));
						return 1;
					})))
				.then(ClientCommands.literal("save")
					.then(ClientCommands.argument("name", StringArgumentType.word()).executes(context -> {
						saveProfile(StringArgumentType.getString(context, "name"));
						return 1;
					})))
				.then(ClientCommands.literal("load")
					.then(ClientCommands.argument("name", StringArgumentType.word()).executes(context -> {
						loadProfile(StringArgumentType.getString(context, "name"));
						return 1;
					})))
				.then(ClientCommands.literal("del")
					.then(ClientCommands.argument("name", StringArgumentType.word()).executes(context -> {
						delProfile(StringArgumentType.getString(context, "name"));
						return 1;
					})))
				.then(ClientCommands.literal("list").executes(context -> {
					listAll();
					return 1;
				}))
				.then(ClientCommands.literal("status").executes(context -> {
					status();
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

	private void stageBless() {
		PendingRequest request = currentRequest();
		if (request == null) {
			feedback(message(tr("plasma.no.pending").withStyle(ChatFormatting.RED)));
			return;
		}
		String hash = request.getPayloadHash();
		blessedHashes.add(hash);
		request.execute();
		feedback(message(tr("plasma.bless.done",
			code(shortHash(hash)),
			code(request.describe())).withStyle(ChatFormatting.GREEN)));
	}

	private void unbless(String hash) {
		String normalized = hash.trim().toLowerCase(java.util.Locale.ROOT);
		if (!blessedHashes.remove(normalized)) {
			feedback(message(tr("plasma.unbless.missing",
				styled(hash, ChatFormatting.DARK_RED, ChatFormatting.ITALIC)).withStyle(ChatFormatting.RED)));
			return;
		}
		feedback(message(tr("plasma.unbless.done",
			code(shortHash(normalized))).withStyle(ChatFormatting.GREEN)));
	}

	private void blockIp(String ip) {
		String normalized = ip.trim();
		blockedIps.add(normalized);
		trustedIps.remove(normalized);
		feedback(message(tr("plasma.block.done",
			styled(normalized, ChatFormatting.DARK_RED, ChatFormatting.ITALIC)).withStyle(ChatFormatting.RED)));
	}

	private void unblockIp(String ip) {
		String normalized = ip.trim();
		if (!blockedIps.remove(normalized)) {
			feedback(message(tr("plasma.unblock.missing",
				styled(normalized, ChatFormatting.DARK_RED, ChatFormatting.ITALIC)).withStyle(ChatFormatting.RED)));
			return;
		}
		feedback(message(tr("plasma.unblock.done",
			styled(normalized, ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC)).withStyle(ChatFormatting.GREEN)));
	}

	private void unlock() {
		if (bridge.unlock()) {
			feedback(message(tr("plasma.unlock.done").withStyle(ChatFormatting.GREEN)));
		} else {
			feedback(message(tr("plasma.unlock.notlocked").withStyle(ChatFormatting.RED)));
		}
	}

	private void saveProfile(String name) {
		PendingRequest request = currentRequest();
		if (request == null) {
			feedback(message(tr("plasma.no.pending").withStyle(ChatFormatting.RED)));
			return;
		}
		profiles.put(name, request.getPayload());
		feedback(message(tr("plasma.profile.saved",
			styled(name, ChatFormatting.AQUA, ChatFormatting.BOLD)).withStyle(ChatFormatting.GREEN)));
	}

	private void loadProfile(String name) {
		if (!profiles.names().contains(name)) {
			feedback(message(tr("plasma.profile.missing",
				styled(name, ChatFormatting.DARK_RED, ChatFormatting.ITALIC)).withStyle(ChatFormatting.RED)));
			return;
		}
		PendingRequest detached = new PendingRequest(bridge, profiles.get(name), "profile:" + name, "127.0.0.1");
		feedback(message(tr("plasma.profile.loaded",
			styled(name, ChatFormatting.AQUA, ChatFormatting.BOLD)).withStyle(ChatFormatting.GREEN)));
		bridge.execute(detached);
	}

	private void delProfile(String name) {
		if (!profiles.remove(name)) {
			feedback(message(tr("plasma.profile.missing",
				styled(name, ChatFormatting.DARK_RED, ChatFormatting.ITALIC)).withStyle(ChatFormatting.RED)));
			return;
		}
		feedback(message(tr("plasma.profile.deleted",
			styled(name, ChatFormatting.AQUA, ChatFormatting.BOLD)).withStyle(ChatFormatting.GREEN)));
	}

	private void listAll() {
		feedback(message(tr("plasma.list.trusted", styled(join(trustedIps), ChatFormatting.DARK_GREEN)).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.list.blocked", styled(join(blockedIps), ChatFormatting.DARK_RED)).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.list.blessed", styled(joinBlessed(), ChatFormatting.GOLD)).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.list.profiles", styled(join(profiles.names()), ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY)));
	}

	private void status() {
		feedback(message(tr("plasma.status.title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
		if (bridge.isRunning()) {
			feedback(message(tr("plasma.status.bridge.open",
				styled(String.valueOf(bridge.getPort()), ChatFormatting.DARK_GREEN, ChatFormatting.BOLD)).withStyle(ChatFormatting.GRAY)));
		} else {
			feedback(message(tr("plasma.status.bridge.closed").withStyle(ChatFormatting.RED)));
		}
		feedback(message(tr("plasma.status.token", code(bridge.getToken())).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.status.echo",
			styled(String.valueOf(echo), echo ? ChatFormatting.GREEN : ChatFormatting.RED)).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.status.locked",
			styled(bridge.isLocked() ? "ACTIVE" : "NONE",
				bridge.isLocked() ? ChatFormatting.DARK_RED : ChatFormatting.DARK_GREEN).withStyle(ChatFormatting.GRAY))));
		feedback(message(tr("plasma.status.attempts",
			code(String.valueOf(bridge.getFailedAttempts())),
			code(String.valueOf(bridge.getMaxAttempts())),
			command("/plasma unlock")).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.status.timeout",
			code(String.valueOf(bridge.getExecutionTimeoutMillis() / 1000)).withStyle(ChatFormatting.GRAY))));
		feedback(message(tr("plasma.status.pending",
			code(String.valueOf(pendingCount())).withStyle(ChatFormatting.GRAY))));
		feedback(message(tr("plasma.status.trusted",
			code(String.valueOf(trustedIps.size())),
			styled(join(trustedIps), ChatFormatting.DARK_GREEN)).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.status.blocked",
			code(String.valueOf(blockedIps.size())),
			styled(join(blockedIps), ChatFormatting.DARK_RED)).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.status.blessed",
			code(String.valueOf(blessedHashes.size())),
			styled(joinBlessed(), ChatFormatting.GOLD)).withStyle(ChatFormatting.GRAY)));
		feedback(message(tr("plasma.status.profiles",
			code(String.valueOf(profiles.size())),
			styled(join(profiles.names()), ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY)));
	}

	private int pendingCount() {
		synchronized (pendingRequests) {
			int count = 0;
			for (PendingRequest request : pendingRequests) {
				if (!request.isResolved()) {
					count++;
				}
			}
			return count;
		}
	}

	private String join(Set<String> items) {
		if (items.isEmpty()) {
			return tr("plasma.list.empty").getString();
		}
		return String.join(", ", items);
	}

	private String joinBlessed() {
		if (blessedHashes.isEmpty()) {
			return tr("plasma.list.empty").getString();
		}
		List<String> shortHashes = new ArrayList<>();
		for (String hash : blessedHashes) {
			shortHashes.add(shortHash(hash));
		}
		return String.join(", ", shortHashes);
	}

	private static String shortHash(String hash) {
		return hash.length() > 12 ? hash.substring(0, 12) + "..." : hash;
	}

	private void agree() {
		if (bridge.isRunning()) {
			feedback(message(tr("plasma.agree.already",
				styled(String.valueOf(bridge.getPort()), ChatFormatting.DARK_GREEN, ChatFormatting.BOLD),
				code(bridge.getToken()),
				command("/plasma close")).withStyle(ChatFormatting.GREEN)));
			return;
		}
		try {
			String token = LocalBridgeConfig.rotate(configDir);
			bridge.setToken(token);
			bridge.start();
			feedback(message(tr("plasma.agree.open",
				styled(String.valueOf(bridge.getPort()), ChatFormatting.DARK_GREEN, ChatFormatting.BOLD),
				code(token),
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
