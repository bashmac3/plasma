package bm3.plasma.client;

import bm3.plasma.LocalBridge;
import bm3.plasma.LocalBridgeConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class PlasmaClient implements ClientModInitializer {
	private static LocalBridge bridge;

	@Override
	public void onInitializeClient() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		try {
			LocalBridgeConfig config = LocalBridgeConfig.load(configDir);
			bridge = new LocalBridge(config.getToken());
			PlasmaGateway gateway = new PlasmaGateway(bridge);
			bridge.setListener(gateway);
			gateway.ready();
		} catch (Exception e) {
			throw new RuntimeException("Unable to start Plasma local bridge", e);
		}
	}
}
