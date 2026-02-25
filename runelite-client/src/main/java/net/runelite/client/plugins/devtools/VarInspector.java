/*
 * Copyright (c) 2018 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
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

	private enum RuleKind
	{
		ID,
		NAME
	}

	private static final class Rule
	{
		final VarType type;
		final RuleKind kind;
		final Integer id;      // if kind == ID
		final String nameRule; // if kind == NAME

		private Rule(VarType type, RuleKind kind, Integer id, String nameRule)
		{
			this.type = type;
			this.kind = kind;
			this.id = id;
			this.nameRule = nameRule;
		}

		static Rule id(VarType type, int id)
		{
			return new Rule(type, RuleKind.ID, id, null);
		}

		static Rule name(VarType type, String rule)
		{
			return new Rule(type, RuleKind.NAME, null, rule);
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Rule))
			{
				return false;
			}
			Rule r = (Rule) o;
			return type == r.type
					&& kind == r.kind
					&& Objects.equals(id, r.id)
					&& Objects.equals(nameRule, r.nameRule);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(type, kind, id, nameRule);
		}
	}

	private final class RuleTableModel extends AbstractTableModel
	{
		private final ArrayList<Rule> rows = new ArrayList<>();

		void setRules(Set<Rule> rules)
		{
			rows.clear();
			rows.addAll(rules);

			rows.sort((a, b) ->
			{
				int t = Integer.compare(a.type.ordinal(), b.type.ordinal());
				if (t != 0)
				{
					return t;
				}

				int k = a.kind.compareTo(b.kind);
				if (k != 0)
				{
					return k;
				}

				if (a.kind == RuleKind.ID)
				{
					return Integer.compare(a.id, b.id);
				}

				// NAME rules
				return a.nameRule.compareToIgnoreCase(b.nameRule);
			});

			fireTableDataChanged();
		}

		Rule getRow(int row)
		{
			return rows.get(row);
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return 3;
		}

		@Override
		public String getColumnName(int col)
		{
			if (col == 0)
			{
				return "TYPE";
			}
			else if (col == 1)
			{
				return "NAME";
			}
			else if (col == 2)
			{
				return "ID";
			}
			return "";
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex)
		{
			Rule r = rows.get(rowIndex);

			if (columnIndex == 0)
			{
				return r.type.getName();
			}
			else if (columnIndex == 1)
			{
				if (r.kind == RuleKind.NAME)
				{
					return r.nameRule;
				}
				// ID rule: show resolved constant name (if known)
				return resolveName(r.type, r.id);
			}
			else if (columnIndex == 2)
			{
				if (r.kind == RuleKind.ID)
				{
					return r.id;
				}
				return "";
			}

			return "";
		}
	}
	private static final String CFG_BLACKLIST_RULES = "varinspector_blacklist_rules";
	private static final String CFG_HIGHLIGHT_RULES = "varinspector_highlight_rules";
	private Set<Rule> blacklistRules;
	private Set<Rule> highlightRules;

	private static final int MAX_LOG_ENTRIES = 10_000;
	private static final int VARBITS_ARCHIVE_ID = 14;

	private static final Map<Integer, String> VARBIT_NAMES = DevToolsPlugin.loadFieldNames(VarbitID.class);
	private static final Map<Integer, String> VARC_NAMES = DevToolsPlugin.loadFieldNames(VarClientID.class);
	private static final Map<Integer, String> VARP_NAMES = DevToolsPlugin.loadFieldNames(VarPlayerID.class);

	private static final String CFG_GROUP = "devtools";
	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final ConfigManager configManager;

	private final JPanel tracker = new JPanel();
	private final RuleTableModel ruleTableModel = new RuleTableModel();
	private final JTable ruleTable = new JTable(ruleTableModel);

	private final JComboBox<VarType> ruleTypeCombo = new JComboBox<>(VarType.values());
	private final JComboBox<RuleKind> ruleKindCombo = new JComboBox<>(RuleKind.values());
	private final JSpinner ruleIdSpinner = new JSpinner();
	private final JTextField ruleNameField = new JTextField(12);

	private int lastTick = 0;

	private int[] oldVarps = null;
	private int[] oldVarps2 = null;

	private Multimap<Integer, Integer> varbits;
	private Map<Integer, Object> varcs = null;

	// --- Filtering/highlighting state (ScriptInspector pattern) ---

	private ListState state = ListState.BLACKLIST;
	private final JTextField filterField = new JTextField(16);


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

//	private Set<Long> getSet()
//	{
//		return state == ListState.BLACKLIST ? blacklist : highlights;
//	}

//	private void refreshList()
//	{
//		listModel.clear();
//		for (Long k : getSet())
//		{
//			listModel.addElement(k);
//		}
//	}

	private void changeState(ListState state)
	{
		this.state = state;
		refreshRuleTable();
	}

//	private void addToSet()
//	{
//		VarType t = (VarType) typeCombo.getSelectedItem();
//		int id = (Integer) idSpinner.getValue();
//		getSet().add(key(t, id));
//		refreshEditorList();
//		idSpinner.setValue(0);
//	}

//	private void removeSelectedFromSet()
//	{
//		int index = jList.getSelectedIndex();
//		if (index == -1)
//		{
//			return;
//		}
//		long k = listModel.get(index);
//		getSet().remove(k);
//		refreshEditorList();
//	}

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

		// --- Load persisted rule sets ---
		blacklistRules = loadRuleSet(CFG_BLACKLIST_RULES);
		highlightRules = loadRuleSet(CFG_HIGHLIGHT_RULES);

		// --- Right side: rule editor (Blacklist / Highlights) ---
		final JPanel rightSide = new JPanel(new BorderLayout());

		final JButton blacklistButton = new JButton("Blacklist");
		blacklistButton.addActionListener(e -> changeState(ListState.BLACKLIST));

		final JButton highlightsButton = new JButton("Highlights");
		highlightsButton.addActionListener(e -> changeState(ListState.HIGHLIGHT));

		final JPanel topRow = new JPanel(new FlowLayout());
		topRow.add(blacklistButton);
		topRow.add(highlightsButton);

		rightSide.add(topRow, BorderLayout.NORTH);

		// Table in center
		ruleTable.setFillsViewportHeight(true);
		rightSide.add(new JScrollPane(ruleTable), BorderLayout.CENTER);

		// Bottom row: Add / Remove + typed inputs
		Component mySpinnerEditor = ruleIdSpinner.getEditor();
		JFormattedTextField textField = ((JSpinner.DefaultEditor) mySpinnerEditor).getTextField();
		textField.setColumns(6);

		ruleNameField.setToolTipText("Name rule. Examples: TROLL_, ^TROLL_, $ACTIVE");

		final JButton addButton = new JButton("Add");
		addButton.addActionListener(e -> addRuleFromInputs());

		final JButton removeButton = new JButton("Remove");
		removeButton.addActionListener(e -> removeSelectedRule());

		final JPanel bottomRow = new JPanel(new FlowLayout());
		bottomRow.add(addButton);
		bottomRow.add(ruleTypeCombo);
		bottomRow.add(ruleKindCombo);
		bottomRow.add(ruleIdSpinner);
		bottomRow.add(ruleNameField);
		bottomRow.add(removeButton);

		rightSide.add(bottomRow, BorderLayout.SOUTH);

		add(rightSide, BorderLayout.EAST);

		// Wire visibility switching for ID vs NAME input
		ruleKindCombo.addActionListener(e -> applyRuleInputVisibility());
		applyRuleInputVisibility();

		// Initial table fill for BLACKLIST
		changeState(ListState.BLACKLIST); // should call refreshRuleTable()

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

		// Unified rule-based matching
		if (matches(blacklistRules, type, id, name))
		{
			return;
		}

		final boolean highlight = matches(highlightRules, type, id, name);

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
		// Persist unified rule sets
		saveRuleSet(CFG_BLACKLIST_RULES, blacklistRules);
		saveRuleSet(CFG_HIGHLIGHT_RULES, highlightRules);

		super.close();
		tracker.removeAll();
		eventBus.unregister(this);
		varcs = null;
		varbits = null;
	}

//	private Set<String> loadStringSet(String keyName)
//	{
//		String cfg = configManager.getConfiguration(CFG_GROUP, keyName);
//		if (cfg == null)
//		{
//			cfg = "";
//		}
//
//		// CSV returns List<String>
//		return new HashSet<>(Text.fromCSV(cfg));
//	}

	private void saveStringSet(String keyName, Set<String> set)
	{
		configManager.setConfiguration(CFG_GROUP, keyName, Text.toCSV(new ArrayList<>(set)));
	}

	private static boolean matchesRule(String rule, String displayName)
	{
		if (rule == null || displayName == null)
		{
			return false;
		}

		String r = rule.trim().toLowerCase();
		if (r.isEmpty())
		{
			return false;
		}

		String nameLower = displayName.toLowerCase();

		// Prefix match: ^FOO
		if (r.charAt(0) == '^')
		{
			return nameLower.startsWith(r.substring(1));
		}

		// Suffix match: $FOO
		if (r.charAt(0) == '$')
		{
			return nameLower.endsWith(r.substring(1));
		}

		// Default: substring match
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

	private boolean matches(Set<Rule> rules, VarType type, int id, String displayName)
	{
		if (rules == null || rules.isEmpty())
		{
			return false;
		}

		for (Rule r : rules)
		{
			// Must match type first
			if (r.type != type)
			{
				continue;
			}

			// Match by ID
			if (r.kind == RuleKind.ID)
			{
				if (r.id != null && r.id == id)
				{
					return true;
				}
			}
			// Match by NAME rule
			else if (r.kind == RuleKind.NAME)
			{
				if (matchesRule(r.nameRule, displayName))
				{
					return true;
				}
			}
		}

		return false;
	}

	private Set<Rule> activeRuleSet()
	{
		return state == ListState.BLACKLIST ? blacklistRules : highlightRules;
	}

	private static String encodeRule(Rule r)
	{
		if (r.kind == RuleKind.ID)
		{
			return "I:" + r.type.ordinal() + ":" + r.id;
		}
		return "N:" + r.type.ordinal() + ":" + r.nameRule;
	}

	private static Rule decodeRule(String s)
	{
		String[] parts = s.split(":", 3);
		if (parts.length != 3)
		{
			throw new IllegalArgumentException("bad rule: " + s);
		}

		RuleKind kind = parts[0].equals("I") ? RuleKind.ID : RuleKind.NAME;
		int typeOrd = Integer.parseInt(parts[1]);
		VarType type = VarType.values()[typeOrd];

		if (kind == RuleKind.ID)
		{
			return Rule.id(type, Integer.parseInt(parts[2]));
		}

		return Rule.name(type, parts[2]);
	}

	private Set<Rule> loadRuleSet(String keyName)
	{
		String cfg = configManager.getConfiguration(CFG_GROUP, keyName);
		if (cfg == null)
		{
			cfg = "";
		}

		try
		{
			return new HashSet<>(Lists.transform(Text.fromCSV(cfg), VarInspector::decodeRule));
		}
		catch (Exception e)
		{
			return new HashSet<>();
		}
	}

	private void saveRuleSet(String keyName, Set<Rule> set)
	{
		configManager.setConfiguration(CFG_GROUP, keyName,
				Text.toCSV(Lists.transform(new ArrayList<>(set), VarInspector::encodeRule)));
	}

	private static String resolveName(VarType type, int id)
	{
		if (type == VarType.VARBIT)
		{
			return VARBIT_NAMES.getOrDefault(id, "");
		}
		else if (type == VarType.VARP)
		{
			return VARP_NAMES.getOrDefault(id, "");
		}
		else
		{
			// VARCINT and VARCSTR share VARC_NAMES
			return VARC_NAMES.getOrDefault(id, "");
		}
	}

	private void applyRuleInputVisibility()
	{
		boolean byId = ruleKindCombo.getSelectedItem() == RuleKind.ID;
		ruleIdSpinner.setVisible(byId);
		ruleNameField.setVisible(!byId);
	}

	private void refreshRuleTable()
	{
		ruleTableModel.setRules(activeRuleSet());
	}

	private void addRuleFromInputs()
	{
		VarType t = (VarType) ruleTypeCombo.getSelectedItem();
		RuleKind k = (RuleKind) ruleKindCombo.getSelectedItem();

		if (k == RuleKind.ID)
		{
			int id = (Integer) ruleIdSpinner.getValue();
			activeRuleSet().add(Rule.id(t, id));
		}
		else
		{
			String s = ruleNameField.getText();
			if (s == null)
			{
				return;
			}

			s = s.trim();
			if (s.isEmpty())
			{
				return;
			}

			activeRuleSet().add(Rule.name(t, s));
			ruleNameField.setText("");
		}

		refreshRuleTable();
	}

	private void removeSelectedRule()
	{
		int row = ruleTable.getSelectedRow();
		if (row == -1)
		{
			return;
		}

		Rule r = ruleTableModel.getRow(row);
		activeRuleSet().remove(r);
		refreshRuleTable();
	}
}