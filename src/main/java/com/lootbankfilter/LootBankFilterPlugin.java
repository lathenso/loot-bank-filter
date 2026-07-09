package com.lootbankfilter;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.text.SimpleDateFormat;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.ItemComposition;
import net.runelite.api.ScriptEvent;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.bank.BankSearch;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Loot Bank Filter
 *
 * 1) Tracks loot received during the session, grouped per source
 *    (NPC name, player name, or event name such as "Barrows").
 *
 * 2) Shows the tracked loot in a sidebar panel (similar to the built-in
 *    Loot Tracker). Each source box has a right-click menu with
 *    "Filter in bank", which filters the bank view to just that source's
 *    drops. The bank button still exists and toggles filtering by ALL
 *    session loot.
 *
 * Threading model (important!):
 *  - Game state, widgets, varcs, and loot events all live on the CLIENT
 *    thread. Anything the Swing panel wants done in-game must be posted
 *    with clientThread.invokeLater(...).
 *  - Swing components must only be touched on the AWT Event Dispatch
 *    Thread (EDT). Anything the client thread wants shown in the panel
 *    must be posted with SwingUtilities.invokeLater(...), and we hand
 *    over immutable snapshots so the EDT never reads live game state.
 */
@Slf4j
@PluginDescriptor(
	name = "Loot Bank Filter",
	description = "Tracks session loot per NPC and filters the bank to show only looted items",
	tags = {"loot", "tracker", "bank", "filter", "search"}
)
public class LootBankFilterPlugin extends Plugin
{
	private static final String BANK_SEARCH_FILTER_EVENT = "bankSearchFilter";
	private static final String ALL_LOOT_LABEL = "all session loot";
	private static final String ALL_TIME_LABEL = "all-time loot";

	/** Config group/key used to persist saved sessions as JSON. */
	private static final String CONFIG_GROUP = "lootbankfilter";
	private static final String KEY_SAVED_SESSIONS = "savedSessions";

	private static final int BUTTON_WIDTH = 60;
	private static final int BUTTON_HEIGHT = 16;
	private static final int BUTTON_X = 50;
	private static final int BUTTON_Y = 9;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private BankSearch bankSearch;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LootBankFilterOverlay overlay;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private LootBankFilterConfig config;

	/** Used to persist/load saved sessions in the RuneLite profile. */
	@Inject
	private ConfigManager configManager;

	/** RuneLite-provided Gson instance for JSON (de)serialization. */
	@Inject
	private Gson gson;

	// ------------------------------------------------------------------
	// State — everything below is only mutated on the client thread.
	// ------------------------------------------------------------------

	/** Per-source loot: source name -> LootSource. Insertion-ordered. */
	private final Map<String, LootSource> lootSources = new LinkedHashMap<>();

	/**
	 * Aggregate of all session loot (canonical item id -> quantity).
	 * Kept in a concurrent map because the overlay reads it during render.
	 */
	@Getter
	private final Map<Integer, Long> sessionLoot = new ConcurrentHashMap<>();

	/** Canonical item id -> looted quantity for the active bank filter. */
	private Map<Integer, Long> activeFilter = Collections.emptyMap();

	/** Human-readable label for the active/pending filter (for messages). */
	private String activeFilterLabel = ALL_LOOT_LABEL;

	/** True while the bank view is actually being filtered. */
	@Getter
	private boolean filterActive;

	/** Set when a filter was chosen from the panel while the bank was closed. */
	private boolean pendingFilter;

	private Widget filterButton;

	private LootBankFilterPanel panel;
	private NavigationButton navButton;

	/** Identity of the current runtime session; also its storage key. */
	private long currentSessionStart;

	/** True when the tracked loot changed since the last save. */
	private boolean dirtySinceSave;

	/** Panel view mode: false = current session only, true = all sessions. */
	private boolean showAllSessions;

	@Provides
	LootBankFilterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LootBankFilterConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);

		currentSessionStart = System.currentTimeMillis();
		dirtySinceSave = false;

		// One-time cleanup of the old single-slot save key from the
		// previous version of this plugin.
		configManager.unsetConfiguration(CONFIG_GROUP, "savedSession");

		panel = new LootBankFilterPanel(this, itemManager);
		navButton = NavigationButton.builder()
			.tooltip("Loot Bank Filter")
			// Loaded from src/main/resources/com/lootbankfilter/icon.png.
			// (The icon.png at the repo ROOT is separate — that one is only
			// used by the Plugin Hub listing, never by the running plugin.)
			.icon(ImageUtil.loadImageResource(LootBankFilterPlugin.class, "icon.png"))
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		clientThread.invokeLater(this::createFilterButton);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);

		clientThread.invokeLater(() ->
		{
			// Last chance to persist before the data is cleared (covers
			// toggling the plugin off; client close is handled by the
			// ClientShutdown subscriber).
			if (config.autoSave() && dirtySinceSave && !lootSources.isEmpty())
			{
				saveCurrentSession(false);
			}

			if (filterActive)
			{
				disableFilter();
			}
			if (filterButton != null)
			{
				filterButton.setHidden(true);
				filterButton = null;
			}
			lootSources.clear();
		});

		sessionLoot.clear();
		filterActive = false;
		pendingFilter = false;
		activeFilter = Collections.emptyMap();
	}

	// =====================================================================
	// 1. LOOT TRACKING (client thread)
	// =====================================================================

	/** Tick of the last pickpocket loot, used to dedupe ServerNpcLoot. */
	private int lastPickpocketTick = -10;

	/**
	 * NPC loot announced by the game's own loot-notification clientscript
	 * (LootManager turns it into this event). This is how modern RuneLite
	 * tracks ALL NPC kills — including loot that never touches the ground,
	 * such as Araxxor corpse harvests and caught implings — and it replaces
	 * the old NpcLootReceived ground-scan event. Subscribing to both would
	 * double-count every regular kill, so we use only this one.
	 */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		// The server also announces NPC loot for pickpockets, but those
		// already arrive as PICKPOCKET-typed LootReceived events; skip the
		// duplicate (the built-in tracker uses the same trick).
		if (Math.abs(client.getTickCount() - lastPickpocketTick) <= 1)
		{
			return;
		}

		recordLoot(Text.removeTags(event.getComposition().getName()), event.getItems());
	}

	/** Loot from PvP kills (still detected via ground items). */
	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		recordLoot(event.getPlayer().getName(), event.getItems());
	}

	/**
	 * Covers "event" loot (Barrows, raids, clues, pickpocketing...).
	 * NPC/PLAYER types are skipped — already counted by the subscribers above.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() == LootRecordType.PICKPOCKET)
		{
			// Remember when we saw pickpocket loot so onServerNpcLoot can
			// skip the server's duplicate announcement for the same target.
			lastPickpocketTick = client.getTickCount();
		}

		if (event.getType() == LootRecordType.NPC || event.getType() == LootRecordType.PLAYER)
		{
			return;
		}
		recordLoot(event.getName(), event.getItems());
	}

	private void recordLoot(String sourceName, Collection<ItemStack> items)
	{
		if (sourceName == null)
		{
			sourceName = "Unknown";
		}

		final LootSource source = lootSources.computeIfAbsent(sourceName, LootSource::new);
		source.kills++;
		source.lastUpdated = System.currentTimeMillis();

		for (ItemStack stack : items)
		{
			final int canonicalId = itemManager.canonicalize(stack.getId());

			if (config.minimumValue() > 0
				&& itemManager.getItemPrice(canonicalId) < config.minimumValue())
			{
				continue;
			}

			// We are on the client thread here, so resolving the item
			// composition (name, high-alch value, tradeability) is safe.
			final TrackedItem existing = source.items.get(canonicalId);
			if (existing != null)
			{
				existing.quantity += stack.getQuantity();
			}
			else
			{
				final ItemComposition comp = itemManager.getItemComposition(canonicalId);
				final TrackedItem newItem = new TrackedItem(
					canonicalId, comp.getName(), comp.getHaPrice(), comp.isTradeable());
				newItem.quantity = stack.getQuantity();
				source.items.put(canonicalId, newItem);
			}

			sessionLoot.merge(canonicalId, (long) stack.getQuantity(), Long::sum);
		}

		dirtySinceSave = true;
		refreshPanel();
	}

	/** Builds an immutable snapshot and ships it to the Swing panel (EDT). */
	private void refreshPanel()
	{
		final Map<String, LootSource> view = buildViewSources();

		// Most recently updated sources first, like the built-in tracker.
		final List<LootSource> ordered = new ArrayList<>(view.values());
		ordered.sort((a, b) -> Long.compare(b.lastUpdated, a.lastUpdated));

		final List<SourceSnapshot> snapshot = new ArrayList<>(ordered.size());
		long totalValue = 0;

		for (LootSource source : ordered)
		{
			long sourceValue = 0;
			final List<ItemSnapshot> items = new ArrayList<>(source.items.size());
			for (TrackedItem item : source.items.values())
			{
				final int gePrice = itemManager.getItemPrice(item.id);
				sourceValue += (long) gePrice * item.quantity;
				items.add(new ItemSnapshot(
					item.id, item.name, item.quantity, gePrice, item.haPrice, item.tradeable));
			}
			totalValue += sourceValue;
			snapshot.add(new SourceSnapshot(source.name, source.kills, sourceValue, items));
		}

		final long total = totalValue;
		SwingUtilities.invokeLater(() -> panel.updatePanel(snapshot, total));
	}

	// =====================================================================
	// 2. ACTIONS CALLED FROM THE PANEL (arrive on EDT, hop to client thread)
	// =====================================================================

	/** Called by the panel when the view selector changes. */
	void setShowAllSessions(boolean allSessions)
	{
		clientThread.invokeLater(() ->
		{
			showAllSessions = allSessions;
			refreshPanel();
		});
	}

	/**
	 * The data behind the panel and the source filters. In current-session
	 * mode this is just the live map; in all-sessions mode it merges every
	 * stored session PLUS the live one. The stored copy of the current
	 * session is skipped, since the live data supersedes its last auto-save
	 * — including it would double-count everything killed since startup.
	 * Callers must treat the result as read-only.
	 */
	private Map<String, LootSource> buildViewSources()
	{
		if (!showAllSessions)
		{
			return lootSources;
		}

		final Map<String, LootSource> merged = new LinkedHashMap<>();

		for (SavedSession saved : readSavedSessions())
		{
			if (saved.startedAt == currentSessionStart || saved.sources == null)
			{
				continue;
			}
			for (SavedSource s : saved.sources)
			{
				final LootSource m = merged.computeIfAbsent(s.name, LootSource::new);
				m.kills += s.kills;
				m.lastUpdated = Math.max(m.lastUpdated,
					s.updatedAt != 0 ? s.updatedAt : saved.savedAt);
				if (s.items == null)
				{
					continue;
				}
				for (SavedItem i : s.items)
				{
					TrackedItem t = m.items.get(i.id);
					if (t == null)
					{
						final ItemComposition comp = itemManager.getItemComposition(i.id);
						t = new TrackedItem(i.id, i.name, comp.getHaPrice(), comp.isTradeable());
						m.items.put(i.id, t);
					}
					t.quantity += i.qty;
				}
			}
		}

		// Merge the live current session on top.
		for (LootSource source : lootSources.values())
		{
			final LootSource m = merged.computeIfAbsent(source.name, LootSource::new);
			m.kills += source.kills;
			m.lastUpdated = Math.max(m.lastUpdated, source.lastUpdated);
			for (TrackedItem item : source.items.values())
			{
				TrackedItem t = m.items.get(item.id);
				if (t == null)
				{
					t = new TrackedItem(item.id, item.name, item.haPrice, item.tradeable);
					m.items.put(item.id, t);
				}
				t.quantity += item.quantity;
			}
		}

		return merged;
	}

	/**
	 * Filters the bank by everything in the ACTIVE view: all current-session
	 * loot, or all-time loot when the panel is in "All sessions" mode. Used
	 * by both the bank button and the panel header's right-click menu.
	 */
	void requestFilterAll()
	{
		clientThread.invokeLater(() ->
		{
			final Map<String, LootSource> view = buildViewSources();
			if (view.isEmpty())
			{
				message(showAllSessions ? "No loot tracked yet." : "No loot tracked yet this session.");
				return;
			}

			final Map<Integer, Long> quantities = new LinkedHashMap<>();
			for (LootSource source : view.values())
			{
				for (TrackedItem item : source.items.values())
				{
					quantities.merge(item.id, item.quantity, Long::sum);
				}
			}
			applyFilter(quantities, showAllSessions ? ALL_TIME_LABEL : ALL_LOOT_LABEL);
		});
	}

	/** Right-click > "Filter in bank" on a source box. */
	void requestFilterBySource(String sourceName)
	{
		clientThread.invokeLater(() ->
		{
			final LootSource source = buildViewSources().get(sourceName);
			if (source == null || source.items.isEmpty())
			{
				message("No items tracked for " + sourceName + ".");
				return;
			}
			final Map<Integer, Long> quantities = new LinkedHashMap<>();
			for (TrackedItem item : source.items.values())
			{
				quantities.put(item.id, item.quantity);
			}
			applyFilter(quantities, sourceName);
		});
	}

	/** Right-click > "Remove from tracker" on a source box. */
	void requestRemoveSource(String sourceName)
	{
		clientThread.invokeLater(() ->
		{
			if (showAllSessions)
			{
				// The merged view is derived data; removal is ambiguous here.
				message("Switch to the 'Current session' view to remove sources.");
				return;
			}

			if (lootSources.remove(sourceName) == null)
			{
				return;
			}

			dirtySinceSave = true;
			rebuildAggregate();

			// If the bank was being filtered by this source, turn it off.
			if (sourceName.equals(activeFilterLabel))
			{
				pendingFilter = false;
				if (filterActive)
				{
					disableFilter();
				}
			}

			refreshPanel();
		});
	}

	/** "Clear all" from the panel or the bank button's right-click. */
	void requestClearAll()
	{
		clientThread.invokeLater(() ->
		{
			lootSources.clear();
			sessionLoot.clear();

			// Clearing starts a fresh session. Anything auto-saved from the
			// previous session stays in storage as history.
			currentSessionStart = System.currentTimeMillis();
			dirtySinceSave = false;

			pendingFilter = false;
			if (filterActive)
			{
				disableFilter();
			}
			refreshPanel();
			message("Session loot cleared.");
		});
	}

	// =====================================================================
	// 2b. SAVE / LOAD SESSIONS (persisted via ConfigManager as JSON)
	//
	// Sessions are stored as a list, newest first, capped by config. Each
	// runtime session has an identity (currentSessionStart) so auto-save
	// keeps updating the SAME stored entry instead of creating a new one
	// every few minutes. Loading an old session adopts its identity, so
	// playing on afterwards keeps auto-saving into that entry — in other
	// words, loading means "resume that session".
	// =====================================================================

	/** "Save session now" from the panel header's right-click menu. */
	void requestSaveSession()
	{
		clientThread.invokeLater(() ->
		{
			if (lootSources.isEmpty())
			{
				message("Nothing to save — no loot tracked this session.");
				return;
			}
			saveCurrentSession(true);
		});
	}

	/** Periodic auto-save; the scheduler calls this on an executor thread. */
	@Schedule(period = 5, unit = ChronoUnit.MINUTES)
	public void autoSaveTick()
	{
		if (!config.autoSave())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (dirtySinceSave && !lootSources.isEmpty())
			{
				saveCurrentSession(false);
			}
		});
	}

	/**
	 * Final auto-save when the client is closing. This runs on the shutdown
	 * thread, not the client thread — but by this point the game loop has
	 * stopped producing loot events, so reading the maps is safe in practice.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		if (config.autoSave() && dirtySinceSave && !lootSources.isEmpty())
		{
			saveCurrentSession(false);
		}
	}

	/**
	 * Serializes the current session and upserts it into the stored list.
	 * Call on the client thread (or during shutdown, see above).
	 */
	private void saveCurrentSession(boolean announce)
	{
		final SavedSession saved = new SavedSession();
		saved.startedAt = currentSessionStart;
		saved.savedAt = System.currentTimeMillis();
		saved.sources = new ArrayList<>(lootSources.size());

		long totalValue = 0;
		for (LootSource source : lootSources.values())
		{
			final SavedSource s = new SavedSource();
			s.name = source.name;
			s.kills = source.kills;
			s.updatedAt = source.lastUpdated;
			s.items = new ArrayList<>(source.items.size());
			for (TrackedItem item : source.items.values())
			{
				final SavedItem i = new SavedItem();
				i.id = item.id;
				i.name = item.name;
				i.qty = item.quantity;
				s.items.add(i);
				totalValue += (long) itemManager.getItemPrice(item.id) * item.quantity;
			}
			saved.sources.add(s);
		}
		saved.totalValue = totalValue;

		final List<SavedSession> sessions = readSavedSessions();
		// Upsert: replace this session's entry, keep newest first, cap size.
		sessions.removeIf(s -> s.startedAt == saved.startedAt);
		sessions.add(0, saved);
		sessions.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
		while (sessions.size() > config.maxSavedSessions())
		{
			sessions.remove(sessions.size() - 1); // drop the oldest
		}
		writeSavedSessions(sessions);

		dirtySinceSave = false;
		if (announce)
		{
			message("Session saved (" + saved.sources.size() + " sources, "
				+ QuantityFormatter.quantityToStackSize(totalValue) + " gp).");
		}
	}

	/** Loads the stored session with the given identity, replacing current data. */
	void requestLoadSession(long sessionId)
	{
		clientThread.invokeLater(() ->
		{
			final SavedSession saved = readSavedSessions().stream()
				.filter(s -> s.startedAt == sessionId)
				.findFirst()
				.orElse(null);

			if (saved == null)
			{
				message("That saved session no longer exists.");
				return;
			}

			lootSources.clear();
			if (saved.sources != null)
			{
				for (SavedSource s : saved.sources)
				{
					final LootSource source = new LootSource(s.name);
					source.kills = s.kills;
					// Old saves lack updatedAt; fall back to the save time.
					source.lastUpdated = s.updatedAt != 0 ? s.updatedAt : saved.savedAt;
					if (s.items != null)
					{
						for (SavedItem i : s.items)
						{
							// Recompute alch value / tradeability from the
							// game cache instead of persisting them; we are
							// on the client thread here so this is safe.
							final ItemComposition comp = itemManager.getItemComposition(i.id);
							final TrackedItem item = new TrackedItem(
								i.id, i.name, comp.getHaPrice(), comp.isTradeable());
							item.quantity = i.qty;
							source.items.put(i.id, item);
						}
					}
					lootSources.put(s.name, source);
				}
			}

			// Adopt the loaded session's identity so auto-save continues
			// updating the same stored entry ("resume session").
			currentSessionStart = saved.startedAt;
			dirtySinceSave = false;

			rebuildAggregate();

			// The active/pending filter may reference stale data; reset it.
			pendingFilter = false;
			if (filterActive)
			{
				disableFilter();
			}

			refreshPanel();
			message("Loaded session from " + formatTime(saved.startedAt) + ".");
		});
	}

	/** Deletes a stored session. Does not touch the currently tracked loot. */
	void requestDeleteSession(long sessionId)
	{
		clientThread.invokeLater(() ->
		{
			final List<SavedSession> sessions = readSavedSessions();
			if (sessions.removeIf(s -> s.startedAt == sessionId))
			{
				writeSavedSessions(sessions);
				message("Saved session deleted.");
			}
		});
	}

	/**
	 * Summaries for the panel's Load/Delete submenus, newest first.
	 * Safe to call from the EDT: only reads config and parses JSON.
	 */
	List<SessionSummary> getSessionSummaries()
	{
		final List<SessionSummary> out = new ArrayList<>();
		for (SavedSession s : readSavedSessions())
		{
			final int sourceCount = s.sources == null ? 0 : s.sources.size();
			out.add(new SessionSummary(s.startedAt,
				formatTime(s.startedAt)
					+ " — " + sourceCount + (sourceCount == 1 ? " source, " : " sources, ")
					+ QuantityFormatter.quantityToStackSize(s.totalValue) + " gp"));
		}
		return out;
	}

	private List<SavedSession> readSavedSessions()
	{
		final String json = configManager.getConfiguration(CONFIG_GROUP, KEY_SAVED_SESSIONS);
		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}
		try
		{
			final SavedSessionList wrapper = gson.fromJson(json, SavedSessionList.class);
			return wrapper != null && wrapper.sessions != null ? wrapper.sessions : new ArrayList<>();
		}
		catch (JsonSyntaxException ex)
		{
			log.warn("Corrupt saved sessions", ex);
			return new ArrayList<>();
		}
	}

	private void writeSavedSessions(List<SavedSession> sessions)
	{
		final SavedSessionList wrapper = new SavedSessionList();
		wrapper.sessions = sessions;
		configManager.setConfiguration(CONFIG_GROUP, KEY_SAVED_SESSIONS, gson.toJson(wrapper));
	}

	private static String formatTime(long millis)
	{
		return new SimpleDateFormat("d MMM yyyy HH:mm").format(new Date(millis));
	}

	/** Panel header > "Delete ALL sessions": wipes stored history too. */
	void requestClearAllSessions()
	{
		clientThread.invokeLater(() ->
		{
			lootSources.clear();
			sessionLoot.clear();
			writeSavedSessions(new ArrayList<>());

			currentSessionStart = System.currentTimeMillis();
			dirtySinceSave = false;
			pendingFilter = false;
			if (filterActive)
			{
				disableFilter();
			}

			refreshPanel();
			message("All sessions deleted, including saved history.");
		});
	}

	private void rebuildAggregate()
	{
		sessionLoot.clear();
		for (LootSource source : lootSources.values())
		{
			for (TrackedItem item : source.items.values())
			{
				sessionLoot.merge(item.id, item.quantity, Long::sum);
			}
		}
	}

	// =====================================================================
	// 3. BANK FILTERING (client thread)
	// =====================================================================

	/**
	 * Central entry point for enabling a filter. If the bank is open the
	 * filter applies immediately; otherwise it is remembered and applied
	 * automatically the next time the bank is opened.
	 */
	private void applyFilter(Map<Integer, Long> quantities, String label)
	{
		activeFilter = quantities;
		activeFilterLabel = label;

		if (config.debugLogging())
		{
			log.info("LBF-DEBUG applyFilter '{}': {} item ids", label, quantities.size());
			quantities.forEach((id, qty) ->
				log.info("LBF-DEBUG   tracked: {} (id={}) x{}",
					itemManager.getItemComposition(id).getName(), id, qty));
		}

		if (isBankOpen())
		{
			activateFilterNow();
			message("Filtering bank by: " + label);
		}
		else
		{
			pendingFilter = true;
			message("Filter set to \"" + label + "\" — it will apply when you open your bank.");
		}
	}

	/** Requires the bank to be open. */
	private void activateFilterNow()
	{
		filterActive = true;
		pendingFilter = false;

		// The single-tab layout mode we use below only works correctly with
		// the main (view-all) vanilla tab selected — with a specific bank
		// tab open, the layout is restricted to that tab and most matches
		// silently vanish. Bank Tags forces this varbit to 0 every time it
		// opens a tag tab for exactly this reason, so we do the same. The
		// server resyncs the player's real tab after the filter is done.
		if (client.getVarbitValue(VarbitID.BANK_CURRENTTAB) != 0)
		{
			client.setVarbit(VarbitID.BANK_CURRENTTAB, 0);
		}

		// No search box involved anymore: the onScriptCallbackEvent handler
		// below answers the bank build script's "getSearchingTagTab" and
		// "bankBuildTab" queries while filterActive, which makes
		// bankmain_build run bankSearchFilter for every slot — exactly like
		// an open Bank Tags tab. A re-layout kicks that off.
		bankSearch.layoutBank();

		updateButtonAppearance();
	}

	/**
	 * Keep the vanilla tab pinned to 0 while the filter is active. The
	 * server resyncs the last-open tab shortly after the bank opens (and
	 * the user can click a tab), either of which would silently restrict
	 * the filtered layout to that tab's contents. RuneLite's own bank
	 * script patch does this same reset for Bank Tags tabs.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (filterActive
			&& event.getVarbitId() == VarbitID.BANK_CURRENTTAB
			&& event.getValue() != 0)
		{
			client.setVarbit(VarbitID.BANK_CURRENTTAB, 0);
			bankSearch.layoutBank();
		}
	}

	private void disableFilter()
	{
		filterActive = false;
		pendingFilter = false;

		// Once the flag is off, the callbacks stop claiming a tag tab is
		// open; re-laying out restores the normal unfiltered view.
		bankSearch.layoutBank();

		updateButtonAppearance();
	}

	private boolean isBankOpen()
	{
		final Widget bank = client.getWidget(ComponentID.BANK_CONTAINER);
		return bank != null && !bank.isHidden();
	}

	/**
	 * The heart of the filter, modeled on how Bank Tags tabs work (which is
	 * why the filter survives Withdraw-X and shows a flat, separator-free
	 * grid — neither is true for search-based filtering):
	 *
	 *  - "getSearchingTagTab": bankmain_build asks whether a tag tab is
	 *    open. Answering 1 makes it run bankSearchFilter for every slot,
	 *    with no search input involved. Because the chatbox search box is
	 *    never opened, entering a Withdraw-X quantity can't cancel it.
	 *  - "bankBuildTab": answering 1 selects the single-tab view, which
	 *    draws the results as one flat grid without tab separator lines
	 *    (the same thing Bank Tags' "remove separators" option does).
	 *  - "bankSearchFilter": the per-slot query; we write 1 (show) or
	 *    0 (hide) to intStack[size - 2] based on the item id at
	 *    intStack[size - 1].
	 *
	 * When filterActive is false we leave every stack untouched so vanilla
	 * search and the real Bank Tags plugin behave normally. (Running this
	 * filter at the same time as an actual Bank Tags tab is a conflict —
	 * whichever plugin answers last wins — so avoid having both active.)
	 */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!filterActive)
		{
			return;
		}

		final int[] intStack = client.getIntStack();
		final int intStackSize = client.getIntStackSize();

		switch (event.getEventName())
		{
			case "getSearchingTagTab":
				intStack[intStackSize - 1] = 1;
				break;

			case "bankBuildTab":
				intStack[intStackSize - 1] = 1; // single-tab view: no separators
				break;

			case BANK_SEARCH_FILTER_EVENT:
			{
				final int itemId = intStack[intStackSize - 1];
				if (itemId < 0)
				{
					// EMPTY bank slots also pass through this filter. We must
					// explicitly exclude them: with no text search active, the
					// vanilla fallback INCLUDES unanswered slots, which lays
					// out hundreds of invisible empty cells in the filtered
					// grid and scatters the real matches all over it. Bank
					// Tags writes 0 for these too when a tag tab is open.
					intStack[intStackSize - 2] = 0;
					return;
				}
				final int canonical = itemManager.canonicalize(itemId);
				final boolean match = activeFilter.containsKey(canonical);
				intStack[intStackSize - 2] = match ? 1 : 0;

				if (config.debugLogging())
				{
					dbgFilterCalls++;
					if (match)
					{
						dbgFilterMatches++;
						log.info("LBF-DEBUG MATCH bankId={} canonical={} name={}",
							itemId, canonical, itemManager.getItemComposition(canonical).getName());
					}
				}
				break;
			}
		}
	}

	// --- diagnostics (active only with the Debug logging config option) ---

	private int dbgFilterCalls;
	private int dbgFilterMatches;

	/** Per-build summary + widget-level truth: what the bank ACTUALLY shows. */
	private void logBuildDiagnostics()
	{
		if (!config.debugLogging() || !filterActive)
		{
			return;
		}

		log.info("LBF-DEBUG build done: filter callback ran {} times, {} matches, activeFilter has {} ids",
			dbgFilterCalls, dbgFilterMatches, activeFilter.size());
		dbgFilterCalls = 0;
		dbgFilterMatches = 0;

		// Independent of the callbacks: inspect the laid-out bank widgets to
		// see which item children ended up visible. If matches above > visible
		// here, something else (another plugin / a later script) is hiding or
		// overriding our results after we set them.
		final Widget itemContainer = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		if (itemContainer == null)
		{
			return;
		}
		int visible = 0;
		final Widget[] children = itemContainer.getDynamicChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child != null && !child.isHidden() && child.getItemId() > 0)
				{
					visible++;
				}
			}
		}
		log.info("LBF-DEBUG bank widgets visible after build: {}", visible);
	}

	// =====================================================================
	// 4. BANK BUTTON (widget creation, client thread)
	// =====================================================================

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != InterfaceID.BANK)
		{
			return;
		}

		filterActive = false;
		filterButton = null;

		clientThread.invokeLater(() ->
		{
			createFilterButton();

			// A filter picked from the panel while the bank was closed?
			// Apply it now that the bank is open.
			if (pendingFilter && !activeFilter.isEmpty())
			{
				activateFilterNow();
				message("Filtering bank by: " + activeFilterLabel);
			}
		});
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANK)
		{
			filterActive = false;
			filterButton = null;
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_BUILD)
		{
			createFilterButton();
			updateButtonAppearance();
			applyLootedQuantities();
			logBuildDiagnostics();
		}
	}

	/**
	 * Cosmetic overlay for the "Show looted quantity" option: after the bank
	 * lays out with our filter active, rewrite each visible stack's displayed
	 * number to the amount LOOTED rather than the amount banked.
	 *
	 * Display only! The server's bank contents are untouched — withdraw
	 * options still act on the real quantities (Withdraw-All takes the whole
	 * real stack), and the real numbers return as soon as the filter is off.
	 */
	private void applyLootedQuantities()
	{
		if (!filterActive || !config.showLootedQuantity())
		{
			return;
		}

		final Widget itemContainer = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		if (itemContainer == null)
		{
			return;
		}

		final Widget[] children = itemContainer.getDynamicChildren();
		if (children == null)
		{
			return;
		}

		for (Widget child : children)
		{
			// Skip empty slots and placeholders (placeholders have qty 0 —
			// overriding those would suggest items that can't be withdrawn).
			if (child == null || child.isHidden()
				|| child.getItemId() < 0 || child.getItemQuantity() <= 0)
			{
				continue;
			}

			final Long looted = activeFilter.get(itemManager.canonicalize(child.getItemId()));
			if (looted != null)
			{
				child.setItemQuantity((int) Math.min(looted, Integer.MAX_VALUE));
			}
		}
	}

	/** Re-layout when the quantity option is toggled mid-filter. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CONFIG_GROUP.equals(event.getGroup())
			&& "showLootedQuantity".equals(event.getKey())
			&& filterActive)
		{
			clientThread.invokeLater(() ->
			{
				if (isBankOpen())
				{
					bankSearch.layoutBank();
				}
			});
		}
	}

	private void createFilterButton()
	{
		final Widget bankContainer = client.getWidget(ComponentID.BANK_CONTAINER);
		if (bankContainer == null)
		{
			return;
		}

		if (filterButton != null && !filterButton.isHidden())
		{
			return;
		}

		filterButton = bankContainer.createChild(-1, WidgetType.TEXT);
		filterButton.setFontId(FontID.PLAIN_11);
		filterButton.setTextShadowed(true);
		filterButton.setOriginalX(BUTTON_X);
		filterButton.setOriginalY(BUTTON_Y);
		filterButton.setOriginalWidth(BUTTON_WIDTH);
		filterButton.setOriginalHeight(BUTTON_HEIGHT);
		filterButton.setAction(0, "Toggle loot filter"); // op 1
		filterButton.setAction(1, "Clear session loot"); // op 2
		filterButton.setHasListener(true);
		filterButton.setOnOpListener((JavaScriptCallback) this::onButtonClicked);
		filterButton.revalidate();
		updateButtonAppearance();
	}

	private void onButtonClicked(ScriptEvent ev)
	{
		if (ev.getOp() == 2)
		{
			requestClearAll();
			return;
		}

		if (filterActive)
		{
			disableFilter();
			return;
		}

		requestFilterAll();
	}

	private void updateButtonAppearance()
	{
		if (filterButton == null)
		{
			return;
		}
		filterButton.setText(filterActive ? "Loot: ON" : "Loot: OFF");
		filterButton.setTextColor(filterActive ? 0x00E000 : 0xFF9040);
	}

	// =====================================================================
	// Helpers
	// =====================================================================

	/** Must be called on the client thread. */
	private void message(String msg)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Loot Bank Filter: " + msg, null);
	}


	// =====================================================================
	// Data holders
	// =====================================================================

	/** Mutable, client-thread-only tracking structures. */
	private static final class LootSource
	{
		private final String name;
		private int kills;
		private long lastUpdated; // millis of the most recent kill/loot
		private final Map<Integer, TrackedItem> items = new LinkedHashMap<>();

		private LootSource(String name)
		{
			this.name = name;
		}
	}

	private static final class TrackedItem
	{
		private final int id;
		private final String name;
		private final int haPrice;
		private final boolean tradeable;
		private long quantity;

		private TrackedItem(int id, String name, int haPrice, boolean tradeable)
		{
			this.id = id;
			this.name = name;
			this.haPrice = haPrice;
			this.tradeable = tradeable;
		}
	}

	/** Immutable snapshots handed to the Swing panel. */
	static final class SourceSnapshot
	{
		final String name;
		final int kills;
		final long value;
		final List<ItemSnapshot> items;

		SourceSnapshot(String name, int kills, long value, List<ItemSnapshot> items)
		{
			this.name = name;
			this.kills = kills;
			this.value = value;
			this.items = items;
		}
	}

	static final class ItemSnapshot
	{
		final int id;
		final String name;
		final long quantity;
		final int gePrice;   // live GE price at snapshot time
		final int haPrice;   // high alchemy value
		final boolean tradeable;

		ItemSnapshot(int id, String name, long quantity, int gePrice, int haPrice, boolean tradeable)
		{
			this.id = id;
			this.name = name;
			this.quantity = quantity;
			this.gePrice = gePrice;
			this.haPrice = haPrice;
			this.tradeable = tradeable;
		}
	}

	/**
	 * JSON shapes for the persisted sessions. Plain mutable fields on
	 * purpose: Gson fills them via reflection, no constructors needed.
	 */
	private static final class SavedSessionList
	{
		List<SavedSession> sessions;
	}

	private static final class SavedSession
	{
		long startedAt;   // session identity + "categorized by date/time"
		long savedAt;     // last time this entry was written
		long totalValue;  // precomputed for menu labels
		List<SavedSource> sources;
	}

	private static final class SavedSource
	{
		String name;
		int kills;
		long updatedAt; // millis of the source's most recent loot
		List<SavedItem> items;
	}

	private static final class SavedItem
	{
		int id;
		String name;
		long qty;
	}

	/** Lightweight summary handed to the panel for its Load/Delete menus. */
	static final class SessionSummary
	{
		final long id;
		final String label;

		SessionSummary(long id, String label)
		{
			this.id = id;
			this.label = label;
		}
	}
}
