package com.lootbankfilter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Place under src/test/java/com/lootbankfilter/ and run main() to launch
 * a development RuneLite client with this plugin loaded.
 *
 * Add the VM option -ea (enable assertions) so the client runs in
 * developer mode, which also gives you the Widget Inspector — very handy
 * for fine-tuning the bank button's position.
 */
public class LootBankFilterPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LootBankFilterPlugin.class);
		RuneLite.main(args);
	}
}
