package com.speakertime;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.ZoneId;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.PluginPanel;

final class SpeakerTimePanel extends PluginPanel
{
    private final JTextField player = new JTextField();
    private final JTextField timeZone = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final List<String> zoneChoices = SpeakerTimePlugin.timeZoneChoices();
    private final JList<String> zoneList = new JList<>();
    private boolean fillingZone;

    SpeakerTimePanel(SpeakerTimePlugin plugin)
    {
        setLayout(new BorderLayout(0, 8));
        add(new JLabel("Manual time zones"), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        JLabel playerLabel = new JLabel("RuneScape name");
        playerLabel.setLabelFor(player);
        form.add(playerLabel);
        form.add(player);

        JLabel zoneLabel = new JLabel("City or time zone");
        zoneLabel.setLabelFor(timeZone);
        form.add(zoneLabel);
        form.add(timeZone);
        form.add(new JLabel("Example: London or LA"));

        JButton save = new JButton("Save manual zone");
        JButton remove = new JButton("Remove manual zone");
        form.add(save);
        form.add(remove);
        form.add(status);
        add(form, BorderLayout.CENTER);
        add(createZoneBrowser(), BorderLayout.SOUTH);

        save.addActionListener(event ->
        {
            ZoneId zone = SpeakerTimePlugin.parseZone(timeZone.getText().strip());
            if (zone == null)
            {
                status.setText("Enter a recognized time zone.");
                return;
            }
            save(plugin, zone);
        });
        remove.addActionListener(event -> save(plugin, null));
    }

    private JPanel createZoneBrowser()
    {
        JPanel browser = new JPanel(new BorderLayout(0, 4));
        JLabel label = new JLabel("Choose a time zone");
        label.setLabelFor(zoneList);
        browser.add(label, BorderLayout.NORTH);

        zoneList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(zoneList);
        scrollPane.setPreferredSize(new Dimension(0, 330));
        browser.add(scrollPane, BorderLayout.CENTER);

        zoneList.addListSelectionListener(event ->
        {
            String selected = zoneList.getSelectedValue();
            if (!event.getValueIsAdjusting() && selected != null)
            {
                fillingZone = true;
                try
                {
                    timeZone.setText(selected);
                }
                finally
                {
                    fillingZone = false;
                }
            }
        });
        timeZone.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent event)
            {
                filterZones();
            }

            @Override
            public void removeUpdate(DocumentEvent event)
            {
                filterZones();
            }

            @Override
            public void changedUpdate(DocumentEvent event)
            {
                filterZones();
            }
        });
        filterZones();
        return browser;
    }

    private void filterZones()
    {
        if (fillingZone)
        {
            return;
        }

        String query = SpeakerTimePlugin.shortcutKey(timeZone.getText());
        zoneList.setListData(zoneChoices.stream()
            .filter(choice -> SpeakerTimePlugin.shortcutKey(choice).contains(query))
            .toArray(String[]::new));
    }

    private void save(SpeakerTimePlugin plugin, ZoneId zone)
    {
        String name = SpeakerTimePlugin.playerKey(player.getText());
        if (name.isEmpty() || name.chars().anyMatch(Character::isISOControl))
        {
            status.setText("Enter a RuneScape name.");
            return;
        }

        status.setText("Saving...");
        plugin.saveManualZone(name, zone, saved ->
            status.setText(!saved ? "Could not save the change."
                : zone == null ? "Manual zone removed." : "Manual zone saved."));
    }
}
