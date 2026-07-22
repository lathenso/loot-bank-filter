package com.lootbankfilter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Sidebar panel, styled loosely after the built-in Loot Tracker:
 * a header with the session total, then one box per loot source
 * (NPC / player / event) showing kill count, value, and item icons.
 *
 * Right-clicking a box opens a context menu:
 *   - "Filter in bank"       -> filters the bank to that source's drops
 *   - "Remove from tracker"  -> drops the source from the session data
 *
 * Right-clicking an individual item tile opens its own menu:
 *   - "Remove from filter"   -> greys the tile and drops the item from this
 *                               source's filter (and the filter-all merge)
 *   - "Add back to filter"   -> restores a previously removed item
 *
 * All methods here run on the AWT Event Dispatch Thread. The panel never
 * touches live game state: the plugin hands it immutable snapshots via
 * {@link #updatePanel}.
 */
class LootBankFilterPanel extends PluginPanel
{
	private static final int ICONS_PER_ROW = 5;

	/** Item tile colors: darker than the box background (30,30,30). */
	private static final Color TILE_COLOR = new Color(22, 22, 22);
	private static final Color TILE_HOVER_COLOR = new Color(34, 34, 34);

	/** Tile treatment for items removed from the filter ("ignored"). */
	private static final Color TILE_IGNORED_COLOR = new Color(16, 16, 16);
	private static final Color TILE_IGNORED_HOVER_COLOR = new Color(26, 26, 26);
	private static final Color TILE_IGNORED_BORDER_COLOR = new Color(70, 70, 70);

	private final LootBankFilterPlugin plugin;
	private final ItemManager itemManager;

	private final JLabel titleLabel = new JLabel("Session Loot");
	private final JLabel totalLabel = new JLabel();
	private final JPanel sourceList = new JPanel();

	/** Source names whose boxes are collapsed (survives panel rebuilds). */
	private final Set<String> collapsedSources = new HashSet<>();

	/** Mirrors the plugin's view mode; set by the selector below. */
	private boolean allSessionsMode;

	/** Last snapshot, kept so collapse toggles can re-render immediately. */
	private List<LootBankFilterPlugin.SourceSnapshot> lastSources = List.of();
	private long lastTotal;

	LootBankFilterPanel(LootBankFilterPlugin plugin, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.itemManager = itemManager;

		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout(0, 10));

		// --- header -----------------------------------------------------
		final JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		header.add(titleLabel, BorderLayout.WEST);

		totalLabel.setFont(FontManager.getRunescapeSmallFont());
		totalLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		header.add(totalLabel, BorderLayout.EAST);

		// Right-click menu on the header: filter, save now, load/delete, clear.
		final JPopupMenu headerMenu = new JPopupMenu();

		final JMenuItem filterAllItem = new JMenuItem("Filter shown loot in bank");
		filterAllItem.addActionListener(e -> plugin.requestFilterAll());
		headerMenu.add(filterAllItem);

		final JMenuItem saveItem = new JMenuItem("Save session now");
		saveItem.addActionListener(e -> plugin.requestSaveSession());
		headerMenu.add(saveItem);

		final JMenu loadMenu = new JMenu("Load session");
		headerMenu.add(loadMenu);

		final JMenu deleteMenu = new JMenu("Delete session");
		headerMenu.add(deleteMenu);

		final JMenuItem clearItem = new JMenuItem("Clear all (start new session)");
		clearItem.addActionListener(e -> plugin.requestClearAll());
		headerMenu.add(clearItem);

		final JMenuItem nukeItem = new JMenuItem("Delete ALL sessions (all-time)");
		nukeItem.addActionListener(e ->
		{
			// Destructive and irreversible — confirm first.
			final int choice = JOptionPane.showConfirmDialog(this,
				"Delete every saved session, the lifetime archive, AND the current session's loot?\n"
					+ "This clears the all-time history and cannot be undone.",
				"Loot Bank Filter",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				plugin.requestClearAllSessions();
			}
		});
		headerMenu.add(nukeItem);

		// Rebuild the Load/Delete submenus each time the popup opens, so
		// they always reflect what is currently stored.
		headerMenu.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
				loadMenu.removeAll();
				deleteMenu.removeAll();

				final List<LootBankFilterPlugin.SessionSummary> sessions = plugin.getSessionSummaries();
				if (sessions.isEmpty())
				{
					for (JMenu menu : new JMenu[]{loadMenu, deleteMenu})
					{
						final JMenuItem none = new JMenuItem("No saved sessions");
						none.setEnabled(false);
						menu.add(none);
					}
					return;
				}

				for (LootBankFilterPlugin.SessionSummary s : sessions)
				{
					final JMenuItem load = new JMenuItem(s.label);
					load.addActionListener(ev -> plugin.requestLoadSession(s.id));
					loadMenu.add(load);

					final JMenuItem del = new JMenuItem(s.label);
					del.addActionListener(ev -> plugin.requestDeleteSession(s.id));
					deleteMenu.add(del);
				}
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
			}
		});

		attachPopup(header, headerMenu);

		// --- view selector: current session vs all sessions ---------------
		final JComboBox<String> viewSelector =
			new JComboBox<>(new String[]{"Current session", "All sessions (total)"});
		viewSelector.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		viewSelector.setFocusable(false);
		viewSelector.addActionListener(e ->
		{
			allSessionsMode = viewSelector.getSelectedIndex() == 1;
			plugin.setShowAllSessions(allSessionsMode);
		});
		// Right-clicking the selector offers the same menu as the header, so
		// "All sessions (total)" can be right-clicked > Filter shown loot.
		viewSelector.setComponentPopupMenu(headerMenu);

		final JPanel north = new JPanel(new BorderLayout(0, 6));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(header, BorderLayout.NORTH);
		north.add(viewSelector, BorderLayout.SOUTH);

		add(north, BorderLayout.NORTH);

		// --- source list --------------------------------------------------
		sourceList.setLayout(new BoxLayout(sourceList, BoxLayout.Y_AXIS));
		sourceList.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Wrap in a plain panel so BoxLayout children stay top-aligned.
		final JPanel listWrapper = new JPanel(new BorderLayout());
		listWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listWrapper.add(sourceList, BorderLayout.NORTH);
		add(listWrapper, BorderLayout.CENTER);

		updatePanel(List.of(), 0);
	}

	/** Called (on the EDT) by the plugin whenever the tracked loot changes. */
	void updatePanel(List<LootBankFilterPlugin.SourceSnapshot> sources, long totalValue)
	{
		// Remember the snapshot so collapse toggles can re-render without
		// waiting for the next update from the plugin.
		lastSources = sources;
		lastTotal = totalValue;

		titleLabel.setText(allSessionsMode ? "All-time Loot" : "Session Loot");
		totalLabel.setText(QuantityFormatter.quantityToStackSize(totalValue) + " gp");

		sourceList.removeAll();

		if (sources.isEmpty())
		{
			final JLabel empty = new JLabel(
				"<html><center>No loot tracked yet.<br>Go kill something!</center></html>");
			empty.setHorizontalAlignment(SwingConstants.CENTER);
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));
			sourceList.add(empty);
		}
		else
		{
			for (LootBankFilterPlugin.SourceSnapshot source : sources)
			{
				sourceList.add(buildSourceBox(source));
				sourceList.add(Box.createVerticalStrut(6));
			}
		}

		sourceList.revalidate();
		sourceList.repaint();
	}

	private JPanel buildSourceBox(LootBankFilterPlugin.SourceSnapshot source)
	{
		final boolean collapsed = collapsedSources.contains(source.name);

		final JPanel box = new JPanel(new BorderLayout());
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		// --- header: "▼ Giant rat  x 12        4.3K gp" --------------------
		final JPanel boxHeader = new JPanel(new BorderLayout());
		boxHeader.setOpaque(false);
		boxHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		final JLabel nameLabel = new JLabel(
			(collapsed ? "\u25B8 " : "\u25BE ") + source.name + "  x " + source.kills);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setToolTipText("Click to " + (collapsed ? "expand" : "collapse"));
		boxHeader.add(nameLabel, BorderLayout.WEST);

		final JLabel valueLabel = new JLabel(QuantityFormatter.quantityToStackSize(source.value) + " gp");
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		boxHeader.add(valueLabel, BorderLayout.EAST);

		// Left-click anywhere on the header toggles collapse. Right-click is
		// untouched so the context menu keeps working. Swing delivers mouse
		// events to the deepest component, so the labels need the listener
		// too, not just the header panel.
		final MouseAdapter collapseToggle = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				if (!collapsedSources.remove(source.name))
				{
					collapsedSources.add(source.name);
				}
				updatePanel(lastSources, lastTotal);
			}
		};
		boxHeader.addMouseListener(collapseToggle);
		nameLabel.addMouseListener(collapseToggle);
		valueLabel.addMouseListener(collapseToggle);

		box.add(boxHeader, BorderLayout.NORTH);

		// --- item icon grid (only when expanded) ---------------------------
		if (!collapsed)
		{
			final JPanel itemGrid = new JPanel(new GridLayout(0, ICONS_PER_ROW, 2, 2));
			itemGrid.setOpaque(false);
			itemGrid.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

			for (LootBankFilterPlugin.ItemSnapshot item : source.items)
			{
				itemGrid.add(buildItemCell(source.name, item));
			}

			// Pad the last row with empty cells so the grid always looks complete.
			final int remainder = source.items.size() % ICONS_PER_ROW;
			if (remainder != 0)
			{
				for (int i = remainder; i < ICONS_PER_ROW; i++)
				{
					final JLabel filler = new JLabel();
					filler.setOpaque(true);
					filler.setBackground(TILE_COLOR);
					filler.setInheritsPopupMenu(true);
					itemGrid.add(filler);
				}
			}

			box.add(itemGrid, BorderLayout.CENTER);
		}

		// --- right-click context menu --------------------------------------
		final JPopupMenu menu = new JPopupMenu();

		final JMenuItem filterItem = new JMenuItem("Filter in bank");
		filterItem.addActionListener(e -> plugin.requestFilterBySource(source.name));
		menu.add(filterItem);

		final JMenuItem removeItem = new JMenuItem("Remove from tracker");
		removeItem.addActionListener(e -> plugin.requestRemoveSource(source.name));
		// The all-time view is derived from stored sessions; removing a
		// source there would be ambiguous, so only allow it per-session.
		removeItem.setEnabled(!allSessionsMode);
		menu.add(removeItem);

		attachPopup(box, menu);
		return box;
	}

	/**
	 * One grid cell: dark square, centered icon, hover highlight, tooltip.
	 * Ignored items (removed from this source's filter) render greyed — a
	 * dimmed grayscale icon, a darker tile, and an outline — and carry their
	 * own right-click menu to add them back. Non-ignored cells carry the
	 * "Remove from filter" menu. Either way the cell's own popup takes
	 * precedence over the source box's inherited menu.
	 */
	private JLabel buildItemCell(String sourceName, LootBankFilterPlugin.ItemSnapshot item)
	{
		final boolean ignored = item.ignored;
		final Color base = ignored ? TILE_IGNORED_COLOR : TILE_COLOR;
		final Color hover = ignored ? TILE_IGNORED_HOVER_COLOR : TILE_HOVER_COLOR;

		final JLabel cell = new JLabel();
		cell.setOpaque(true);
		cell.setBackground(base);
		cell.setHorizontalAlignment(SwingConstants.CENTER);
		cell.setVerticalAlignment(SwingConstants.CENTER);
		cell.setPreferredSize(new Dimension(40, 36));
		cell.setToolTipText(buildTooltip(item));
		if (ignored)
		{
			cell.setBorder(BorderFactory.createLineBorder(TILE_IGNORED_BORDER_COLOR));
		}

		// AsyncBufferedImage repaints the label once the sprite loads. For
		// ignored items we wait for the load, then swap in a dimmed grayscale
		// version so the tile clearly reads as "not in the filter".
		final AsyncBufferedImage img = itemManager.getImage(
			item.id, (int) Math.min(item.quantity, Integer.MAX_VALUE), item.quantity > 1);
		if (ignored)
		{
			img.onLoaded(() ->
			{
				final BufferedImage disabled = toDisabledIcon(img);
				SwingUtilities.invokeLater(() ->
				{
					cell.setIcon(new ImageIcon(disabled));
					cell.repaint();
				});
			});
		}
		else
		{
			img.addTo(cell);
		}

		// Subtle hover highlight, like the built-in Loot Tracker.
		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				cell.setBackground(hover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				cell.setBackground(base);
			}
		});

		// Per-item right-click menu (overrides the source box's inherited one).
		final JPopupMenu itemMenu = new JPopupMenu();
		if (ignored)
		{
			final JMenuItem addBack = new JMenuItem("Add back to filter");
			addBack.addActionListener(e -> plugin.requestUnignoreItem(sourceName, item.id));
			itemMenu.add(addBack);
		}
		else
		{
			final JMenuItem remove = new JMenuItem("Remove from filter");
			remove.addActionListener(e -> plugin.requestIgnoreItem(sourceName, item.id));
			itemMenu.add(remove);
		}
		cell.setComponentPopupMenu(itemMenu);

		return cell;
	}

	/**
	 * Grayscale + dim an item icon for the "removed from filter" look. Pure
	 * AWT pixel work so it doesn't depend on any RuneLite image helper.
	 * Must be called after the source AsyncBufferedImage has loaded.
	 */
	private static BufferedImage toDisabledIcon(BufferedImage src)
	{
		final int w = src.getWidth();
		final int h = src.getHeight();
		final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				final int argb = src.getRGB(x, y);
				final int a = (argb >>> 24) & 0xFF;
				if (a == 0)
				{
					continue; // keep fully transparent pixels transparent
				}
				final int r = (argb >> 16) & 0xFF;
				final int g = (argb >> 8) & 0xFF;
				final int b = argb & 0xFF;
				final int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
				final int da = a * 45 / 100; // dim to ~45% opacity
				out.setRGB(x, y, (da << 24) | (lum << 16) | (lum << 8) | lum);
			}
		}
		return out;
	}

	/**
	 * Hover tooltip: name and quantity, GE price (per item and total) if
	 * the item is tradeable, and the High Alchemy value (per item and
	 * total). Untradeables show only the HA line.
	 */
	private static String buildTooltip(LootBankFilterPlugin.ItemSnapshot item)
	{
		final StringBuilder sb = new StringBuilder("<html><b>")
			.append(item.name)
			.append("</b> x ")
			.append(QuantityFormatter.formatNumber(item.quantity));

		if (item.tradeable)
		{
			sb.append("<br>GE: ")
				.append(QuantityFormatter.formatNumber(item.gePrice))
				.append(" gp ea &#8212; ")
				.append(QuantityFormatter.formatNumber((long) item.gePrice * item.quantity))
				.append(" gp total");
		}

		sb.append("<br>HA: ")
			.append(QuantityFormatter.formatNumber(item.haPrice))
			.append(" gp ea &#8212; ")
			.append(QuantityFormatter.formatNumber((long) item.haPrice * item.quantity))
			.append(" gp total");

		if (item.ignored)
		{
			sb.append("<br><i>Removed from filter &#8212; right-click to add back</i>");
		}

		return sb.append("</html>").toString();
	}

	/**
	 * Attach the popup to a component and its children. Components that
	 * already carry their own popup (e.g. item tiles, which have their own
	 * "Remove from filter" / "Add back to filter" menu) are left untouched
	 * so the source-box menu doesn't overwrite them.
	 */
	private static void attachPopup(JComponent component, JPopupMenu menu)
	{
		if (component.getComponentPopupMenu() == null)
		{
			component.setComponentPopupMenu(menu);
		}
		for (java.awt.Component child : component.getComponents())
		{
			if (child instanceof JComponent)
			{
				attachPopup((JComponent) child, menu);
			}
		}
	}
}
