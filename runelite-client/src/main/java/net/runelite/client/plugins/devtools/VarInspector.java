package net.runelite.client.plugins.devtools;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;
import net.runelite.api.VarbitComposition;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.Text;

class VarInspector extends DevToolsFrame
{
	@Getter
	private enum VarType
	{
		VARBIT("Varbit"),
		VARP("VarPlayer"),
		VARCINT("VarClientInt"),
		VARCSTR("VarClientStr");

		private final String name;
		private final JCheckBox checkBox;

		VarType(String name)
		{
			this.name = name;
			checkBox = new JCheckBox(name, true);
		}
	}

	private enum ListState
	{
		BLACKLIST,
		HIGHLIGHT
	}
	private enum EditMode { IDS, NAMES }
	private EditMode editMode = EditMode.IDS;

	private static final int MAX_LOG_ENTRIES = 10_000;
	private static final int VARBITS_ARCHIVE_ID = 14;

	private static final Map<Integer, String> VARBIT_NAMES = DevToolsPlugin.loadFieldNames(VarbitID.class);
	private static final Map<Integer, String> VARC_NAMES = DevToolsPlugin.loadFieldNames(VarClientID.class);
	private static final Map<Integer, String> VARP_NAMES = DevToolsPlugin.loadFieldNames(VarPlayerID.class);

	private static final String CFG_GROUP = "devtools";
	private static final String CFG_BLACKLIST = "varinspector_blacklist";
	private static final String CFG_HIGHLIGHTS = "varinspector_highlights";
	private static final String CFG_BLACKLIST_NAMES = "varinspector_blacklist_names";
	private static final String CFG_HIGHLIGHTS_NAMES = "varinspector_highlights_names";
	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final ConfigManager configManager;

	private final JPanel tracker = new JPanel();

	private int lastTick = 0;

	private int[] oldVarps = null;
	private int[] oldVarps2 = null;

	private Multimap<Integer, Integer> varbits;
	private Map<Integer, Object> varcs = null;

	// --- Filtering/highlighting state (ScriptInspector pattern) ---
	private Set<Long> blacklist;
	private Set<Long> highlights;
	private Set<String> blacklistNames;
	private Set<String> highlightNames;

	private final DefaultListModel<String> nameListModel = new DefaultListModel<>();
	private final JList<String> nameList = new JList<>(nameListModel);

	private final DefaultListModel<Long> listModel = new DefaultListModel<>();
	private final JList<Long> jList = new JList<>(listModel);
	private ListState state = ListState.BLACKLIST;

	private final JComboBox<VarType> typeCombo = new JComboBox<>(VarType.values());
	private final JSpinner idSpinner = new JSpinner();
	private final JTextField filterField = new JTextField(16);
	private final JTextField nameRuleField = new JTextField(12);
	private JScrollPane editorScroll; // holds either jList or nameList


	private static long key(VarType type, int id)
	{
		return ((long) type.ordinal() << 32) | (id & 0xFFFFFFFFL);
	}

	private static String keyToString(long k)
	{
		int typeOrd = (int) (k >>> 32);
		long id = k & 0xFFFFFFFFL;
		return typeOrd + ":" + id;
	}

	private static long stringToKey(String s)
	{
		int colon = s.indexOf(':');
		if (colon <= 0)
		{
			throw new NumberFormatException("bad key: " + s);
		}
		int typeOrd = Integer.parseInt(s.substring(0, colon));
		long id = Long.parseLong(s.substring(colon + 1));
		return ((long) typeOrd << 32) | (id & 0xFFFFFFFFL);
	}

	private Set<Long> getSet()
	{
		return state == ListState.BLACKLIST ? blacklist : highlights;
	}

	private void refreshList()
	{
		listModel.clear();
		for (Long k : getSet())
		{
			listModel.addElement(k);
		}
	}

	private void changeState(ListState state)
	{
		this.state = state;
		refreshEditorList();
	}

	private void addToSet()
	{
		VarType t = (VarType) typeCombo.getSelectedItem();
		int id = (Integer) idSpinner.getValue();
		getSet().add(key(t, id));
		refreshEditorList();
		idSpinner.setValue(0);
	}

	private void removeSelectedFromSet()
	{
		int index = jList.getSelectedIndex();
		if (index == -1)
		{
			return;
		}
		long k = listModel.get(index);
		getSet().remove(k);
		refreshEditorList();
	}

	@Inject
	VarInspector(Client client, ClientThread clientThread, EventBus eventBus, ConfigManager configManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.configManager = configManager;

		setTitle("RuneLite Var Inspector");
		setLayout(new BorderLayout());

		tracker.setLayout(new DynamicGridLayout(0, 1, 0, 3));

		final JPanel trackerWrapper = new JPanel(new BorderLayout());
		trackerWrapper.add(tracker, BorderLayout.NORTH);

		final JScrollPane trackerScroller = new JScrollPane(trackerWrapper);
		trackerScroller.setPreferredSize(new Dimension(520, 420));

		final JScrollBar vertical = trackerScroller.getVerticalScrollBar();
		vertical.addAdjustmentListener(new AdjustmentListener()
		{
			int lastMaximum = actualMax();

			private int actualMax()
			{
				return vertical.getMaximum() - vertical.getModel().getExtent();
			}

			@Override
			public void adjustmentValueChanged(AdjustmentEvent e)
			{
				if (vertical.getValue() >= lastMaximum)
				{
					vertical.setValue(actualMax());
				}
				lastMaximum = actualMax();
			}
		});

		add(trackerScroller, BorderLayout.CENTER);

		// --- Bottom row: per-type checkboxes + text filter + clear ---
		final JPanel trackerOpts = new JPanel(new FlowLayout());
		for (VarType cb : VarType.values())
		{
			trackerOpts.add(cb.getCheckBox());
		}

		trackerOpts.add(new JLabel("Filter:"));
		filterField.setToolTipText("Substring match against name/id/type");
		trackerOpts.add(filterField);

		final JButton clearBtn = new JButton("Clear");
		clearBtn.addActionListener(e ->
		{
			tracker.removeAll();
			tracker.revalidate();
		});
		trackerOpts.add(clearBtn);

		add(trackerOpts, BorderLayout.SOUTH);

		// --- Load persisted blacklist/highlights (ScriptInspector pattern) ---
		blacklist = loadKeySet(CFG_BLACKLIST);
		highlights = loadKeySet(CFG_HIGHLIGHTS);

		blacklistNames = loadStringSet(CFG_BLACKLIST_NAMES);
		highlightNames = loadStringSet(CFG_HIGHLIGHTS_NAMES);

		// --- Right side: list editor (Blacklist / Highlights) ---
		final JPanel rightSide = new JPanel(new BorderLayout());

		final JButton blacklistButton = new JButton("Blacklist");
		blacklistButton.addActionListener(e -> changeState(ListState.BLACKLIST));

		final JButton highlightsButton = new JButton("Highlights");
		highlightsButton.addActionListener(e -> changeState(ListState.HIGHLIGHT));

		final JButton idsButton = new JButton("IDs");
		idsButton.addActionListener(e -> changeEditMode(EditMode.IDS));

		final JButton namesButton = new JButton("Names");
		namesButton.addActionListener(e -> changeEditMode(EditMode.NAMES));

		final JPanel topRow = new JPanel(new FlowLayout());
		topRow.add(blacklistButton);
		topRow.add(highlightsButton);
		topRow.add(idsButton);
		topRow.add(namesButton);

		rightSide.add(topRow, BorderLayout.NORTH);

		nameList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		nameList.setCellRenderer((list, value, index, isSelected, cellHasFocus) ->
		{
			JLabel lbl = new JLabel(value);
			lbl.setOpaque(true);
			lbl.setBackground(isSelected ? ColorScheme.DARKER_GRAY_COLOR : list.getBackground());
			return lbl;
		});

//		rightSide.add(new JScrollPane(jList), BorderLayout.CENTER);

		Component mySpinnerEditor = idSpinner.getEditor();
		JFormattedTextField textField = ((JSpinner.DefaultEditor) mySpinnerEditor).getTextField();
		textField.setColumns(6);

		final JPanel bottomRow = new JPanel(new FlowLayout());

		final JButton addButton = new JButton("Add");
		addButton.addActionListener(e -> addToEditor());

		final JButton removeButton = new JButton("Remove");
		removeButton.addActionListener(e -> removeSelectedFromEditor());

		nameRuleField.setToolTipText("Name rule. Examples: TROLL_, ^TROLL_, $ACTIVE");

		bottomRow.add(addButton);
		bottomRow.add(typeCombo);
		bottomRow.add(idSpinner);
		bottomRow.add(nameRuleField);
		bottomRow.add(removeButton);

		rightSide.add(bottomRow, BorderLayout.SOUTH);

		editorScroll = new JScrollPane();
		rightSide.add(editorScroll, BorderLayout.CENTER);

		changeState(ListState.BLACKLIST);
		add(rightSide, BorderLayout.EAST);

		// Initialize editor UI
		applyEditorVisibility();
		refreshEditorList();
		updateListViewport();

		pack();
	}

	private Set<Long> loadKeySet(String keyName)
	{
		String cfg = configManager.getConfiguration(CFG_GROUP, keyName);
		if (cfg == null)
		{
			cfg = "";
		}

		try
		{
			return new HashSet<>(Lists.transform(Text.fromCSV(cfg), VarInspector::stringToKey));
		}
		catch (Exception e)
		{
			return new HashSet<>();
		}
	}

	private void saveKeySet(String keyName, Set<Long> set)
	{
		configManager.setConfiguration(CFG_GROUP, keyName,
				Text.toCSV(Lists.transform(new ArrayList<>(set), VarInspector::keyToString)));
	}

	private void addVarLog(VarType type, int id, String name, int old, int neew)
	{
		addVarLog(type, id, name, Integer.toString(old), Integer.toString(neew));
	}

	private void addVarLog(VarType type, int id, String name, String old, String neew)
	{
		if (!type.getCheckBox().isSelected())
		{
			return;
		}

		long k = key(type, id);

		// Blacklist by ID OR by name rule
		if (blacklist.contains(k) || matchesAnyNameRule(blacklistNames, name))
		{
			return;
		}

		final boolean highlight = highlights.contains(k) || matchesAnyNameRule(highlightNames, name);

		int tick = client.getTickCount();
		SwingUtilities.invokeLater(() ->
		{
			if (tick != lastTick)
			{
				lastTick = tick;
				JLabel header = new JLabel("Tick " + tick);
				header.setFont(FontManager.getRunescapeSmallFont());
				header.setBorder(new CompoundBorder(
						BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.LIGHT_GRAY_COLOR),
						BorderFactory.createEmptyBorder(3, 6, 0, 0)
				));
				tracker.add(header);
			}

			JLabel line = new JLabel(String.format("%s %s changed: %s -> %s", type.getName(), name, old, neew));

			if (highlight)
			{
				line.setOpaque(true);
				line.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				line.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
				line.setFont(line.getFont().deriveFont(Font.BOLD));
			}

			tracker.add(line);

			while (tracker.getComponentCount() > MAX_LOG_ENTRIES)
			{
				tracker.remove(0);
			}

			tracker.revalidate();
		});
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		int index = varbitChanged.getIndex();
		int[] varps = client.getVarps();

		// Check varbits
		for (int i : varbits.get(index))
		{
			int old = client.getVarbitValue(oldVarps, i);
			int neew = client.getVarbitValue(varps, i);
			if (old != neew)
			{
				client.setVarbitValue(oldVarps2, i, neew);

				final String name = VARBIT_NAMES.getOrDefault(i, Integer.toString(i));
				addVarLog(VarType.VARBIT, i, name, old, neew);
			}
		}

		// Check varps
		int old = oldVarps2[index];
		int neew = varps[index];
		if (old != neew)
		{
			String name = VARP_NAMES.get(index);
			if (name != null)
			{
				name += "(" + index + ")";
			}
			else
			{
				name = Integer.toString(index);
			}
			addVarLog(VarType.VARP, index, name, old, neew);
		}

		System.arraycopy(client.getVarps(), 0, oldVarps, 0, oldVarps.length);
		System.arraycopy(client.getVarps(), 0, oldVarps2, 0, oldVarps2.length);
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged e)
	{
		int idx = e.getIndex();
		int neew = (Integer) client.getVarcMap().getOrDefault(idx, 0);
		int old = (Integer) varcs.getOrDefault(idx, 0);
		varcs.put(idx, neew);

		if (old != neew)
		{
			final String name = VARC_NAMES.getOrDefault(idx, Integer.toString(idx));
			addVarLog(VarType.VARCINT, idx, name, old, neew);
		}
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged e)
	{
		int idx = e.getIndex();
		String neew = (String) client.getVarcMap().getOrDefault(idx, "");
		String old = (String) varcs.getOrDefault(idx, "");
		varcs.put(idx, neew);

		if (!Objects.equals(old, neew))
		{
			final String name = VARC_NAMES.getOrDefault(idx, Integer.toString(idx));
			if (old != null)
			{
				old = "\"" + old + "\"";
			}
			else
			{
				old = "null";
			}
			if (neew != null)
			{
				neew = "\"" + neew + "\"";
			}
			else
			{
				neew = "null";
			}
			addVarLog(VarType.VARCSTR, idx, name, old, neew);
		}
	}

	@Override
	public void open()
	{
		if (oldVarps == null)
		{
			oldVarps = new int[client.getVarps().length];
			oldVarps2 = new int[client.getVarps().length];
		}

		System.arraycopy(client.getVarps(), 0, oldVarps, 0, oldVarps.length);
		System.arraycopy(client.getVarps(), 0, oldVarps2, 0, oldVarps2.length);
		varcs = new HashMap<>(client.getVarcMap());
		varbits = HashMultimap.create();

		clientThread.invoke(() ->
		{
			IndexDataBase indexVarbits = client.getIndexConfig();
			final int[] varbitIds = indexVarbits.getFileIds(VARBITS_ARCHIVE_ID);
			for (int id : varbitIds)
			{
				VarbitComposition varbit = client.getVarbit(id);
				if (varbit != null)
				{
					varbits.put(varbit.getIndex(), id);
				}
			}
		});

		eventBus.register(this);
		super.open();
	}

	@Override
	public void close()
	{
		// Persist sets (ScriptInspector pattern)
		saveKeySet(CFG_HIGHLIGHTS, highlights);
		saveKeySet(CFG_BLACKLIST, blacklist);
		saveStringSet(CFG_BLACKLIST_NAMES, blacklistNames);
		saveStringSet(CFG_HIGHLIGHTS_NAMES, highlightNames);

		super.close();
		tracker.removeAll();
		eventBus.unregister(this);
		varcs = null;
		varbits = null;
	}

	private Set<String> loadStringSet(String keyName)
	{
		String cfg = configManager.getConfiguration(CFG_GROUP, keyName);
		if (cfg == null)
		{
			cfg = "";
		}

		// CSV returns List<String>
		return new HashSet<>(Text.fromCSV(cfg));
	}

	private void saveStringSet(String keyName, Set<String> set)
	{
		configManager.setConfiguration(CFG_GROUP, keyName, Text.toCSV(new ArrayList<>(set)));
	}

	private void changeEditMode(EditMode mode)
	{
		this.editMode = mode;
		refreshEditorList();
		applyEditorVisibility();
		updateListViewport();
	}

	private void refreshEditorList()
	{
		if (editMode == EditMode.IDS)
		{
			refreshList();
		}
		else
		{
			nameListModel.clear();
			Set<String> s = (state == ListState.BLACKLIST) ? blacklistNames : highlightNames;
			for (String v : s)
			{
				nameListModel.addElement(v);
			}
		}
	}

	private void updateListViewport()
	{
		if (editorScroll == null)
		{
			return;
		}

		if (editMode == EditMode.IDS)
		{
			editorScroll.setViewportView(jList);
		}
		else
		{
			editorScroll.setViewportView(nameList);
		}
	}

	private void applyEditorVisibility()
	{
		boolean ids = editMode == EditMode.IDS;
		typeCombo.setVisible(ids);
		idSpinner.setVisible(ids);

		nameRuleField.setVisible(!ids);
	}

	private void addToEditor()
	{
		if (editMode == EditMode.IDS)
		{
			addToSet();
			return;
		}

		String rule = nameRuleField.getText();
		if (rule == null)
		{
			return;
		}
		rule = rule.trim();
		if (rule.isEmpty())
		{
			return;
		}

		Set<String> s = (state == ListState.BLACKLIST) ? blacklistNames : highlightNames;
		s.add(rule);
		nameRuleField.setText("");
		refreshEditorList();
	}

	private void removeSelectedFromEditor()
	{
		if (editMode == EditMode.IDS)
		{
			removeSelectedFromSet();
			return;
		}

		int index = nameList.getSelectedIndex();
		if (index == -1)
		{
			return;
		}

		String rule = nameListModel.get(index);
		Set<String> s = (state == ListState.BLACKLIST) ? blacklistNames : highlightNames;
		s.remove(rule);
		refreshEditorList();
	}

	private static boolean matchesRule(String rule, String nameLower)
	{
		if (rule == null)
		{
			return false;
		}

		String r = rule.trim().toLowerCase();
		if (r.isEmpty())
		{
			return false;
		}

		if (r.charAt(0) == '^')
		{
			return nameLower.startsWith(r.substring(1));
		}
		if (r.charAt(0) == '$')
		{
			return nameLower.endsWith(r.substring(1));
		}

		return nameLower.contains(r);
	}

	private boolean matchesAnyNameRule(Set<String> rules, String displayName)
	{
		if (displayName == null || rules == null || rules.isEmpty())
		{
			return false;
		}

		String nameLower = displayName.toLowerCase();
		for (String rule : rules)
		{
			if (matchesRule(rule, nameLower))
			{
				return true;
			}
		}
		return false;
	}

}