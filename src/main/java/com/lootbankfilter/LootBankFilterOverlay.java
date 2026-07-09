package com.lootbankfilter;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Comparator;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

/**
 * Simple session-loot panel. Overlay rendering happens on the client thread,
 * so it is safe to resolve item compositions and prices here.
 */
public class LootBankFilterOverlay extends OverlayPanel
{
	private final LootBankFilterPlugin plugin;
	private final LootBankFilterConfig config;
	private final ItemManager itemManager;

	@Inject
	LootBankFilterOverlay(LootBankFilterPlugin plugin, LootBankFilterConfig config, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Map<Integer, Long> loot = plugin.getSessionLoot();

		if (!config.showOverlay() || loot.isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Session Loot")
			.build());

		long totalValue = 0;

		// Sort by total stack value, highest first.
		final Comparator<Map.Entry<Integer, Long>> byValueDesc =
			Comparator.comparingLong((Map.Entry<Integer, Long> e) ->
				(long) itemManager.getItemPrice(e.getKey()) * e.getValue()).reversed();

		int shown = 0;
		for (Map.Entry<Integer, Long> entry : loot.entrySet().stream().sorted(byValueDesc).toArray(Map.Entry[]::new))
		{
			totalValue += (long) itemManager.getItemPrice(entry.getKey()) * entry.getValue();

			if (shown++ < config.overlayMaxItems())
			{
				final String name = itemManager.getItemComposition(entry.getKey()).getName();
				panelComponent.getChildren().add(LineComponent.builder()
					.left(name)
					.right("x" + QuantityFormatter.quantityToStackSize(entry.getValue()))
					.build());
			}
		}

		if (loot.size() > config.overlayMaxItems())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("...")
				.right((loot.size() - config.overlayMaxItems()) + " more")
				.build());
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Total value:")
			.right(QuantityFormatter.quantityToStackSize(totalValue) + " gp")
			.build());

		return super.render(graphics);
	}
}
