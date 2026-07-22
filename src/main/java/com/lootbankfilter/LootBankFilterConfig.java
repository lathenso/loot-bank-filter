package com.lootbankfilter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("lootbankfilter")
public interface LootBankFilterConfig extends Config
{
	@ConfigItem(
		keyName = "minimumValue",
		name = "Minimum item value",
		description = "Ignore drops whose per-unit GE price is below this (0 = track everything)",
		position = 0
	)
	default int minimumValue()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show session loot overlay",
		description = "Display an on-screen panel listing loot tracked this session",
		position = 1
	)
	default boolean showOverlay()
	{
		return true;
	}

	@Range(min = 1, max = 25)
	@ConfigItem(
		keyName = "overlayMaxItems",
		name = "Overlay max items",
		description = "Maximum number of item lines shown on the overlay (sorted by total value)",
		position = 2
	)
	default int overlayMaxItems()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "autoSave",
		name = "Auto-save session",
		description = "Automatically save the session on the interval below, and when the client closes",
		position = 3
	)
	default boolean autoSave()
	{
		return true;
	}

	@Range(min = 1, max = 30)
	@ConfigItem(
		keyName = "autoSaveMinutes",
		name = "Auto-save interval (min)",
		description = "How often the session auto-saves while tracking, in minutes (only applies when auto-save is on)",
		position = 4
	)
	default int autoSaveMinutes()
	{
		return 1;
	}

	@Range(min = 1, max = 50)
	@ConfigItem(
		keyName = "maxSavedSessions",
		name = "Max saved sessions",
		description = "How many saved sessions to keep; the oldest are folded into the permanent lifetime archive first",
		position = 5
	)
	default int maxSavedSessions()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "showLootedQuantity",
		name = "Show looted quantity",
		description = "While the filter is active, bank stacks display the amount you looted instead of the amount banked. Display only — withdraw options still use the real bank quantities.",
		position = 6
	)
	default boolean showLootedQuantity()
	{
		return false;
	}

	@ConfigItem(
		keyName = "debugLogging",
		name = "Debug logging",
		description = "Writes filter diagnostics to the client log/console. Only needed when troubleshooting.",
		position = 7
	)
	default boolean debugLogging()
	{
		return false;
	}
}
