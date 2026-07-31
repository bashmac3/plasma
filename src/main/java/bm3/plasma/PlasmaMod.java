package bm3.plasma;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlasmaMod implements ModInitializer {
	public static final String MOD_ID = "plasma";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		printSecurityWarning();
	}

	public static void printSecurityWarning() {
		LOGGER.warn(BANNER);
	}

	public static String banner() {
		return BANNER;
	}

	private static final String BANNER = """
--- !!! WARNING !!! ---
This mod allows Remote Code Execution!

If you did not intend to install this mod, delete it immediately and start a full antivirus scan.

Or, if you installed this mod with a purpose, you should follow these precautions:
  1. Never join servers with this mod installed:
  	When connecting to the server, the server gets your IP Address, and the server can easily start executing payloads.
  2. Play on an offline account:
	An attacker with the right payload can steal you Microsoft account token.

I (bm3 or bashmac3) WILL NOT BE HELD LIABLE FOR ANY DAMAGE CAUSED.
Proceed with extreme caution.
""";
}
