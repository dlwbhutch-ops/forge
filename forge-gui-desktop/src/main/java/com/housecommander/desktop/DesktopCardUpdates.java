package com.housecommander.desktop;

import com.housecommander.core.CardDataUpdates;
import java.awt.*;
import java.io.InputStream;
import java.net.URI;
import javax.swing.*;

public final class DesktopCardUpdates {
    private DesktopCardUpdates() { }
    public static void install(JFrame frame) {
        Container content = frame.getContentPane();
        if (!(content.getLayout() instanceof BorderLayout)) return;
        Component header = ((BorderLayout) content.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        if (header instanceof JPanel) ((JPanel) header).add(button(frame), BorderLayout.EAST);
    }
    public static JButton button(Component owner) {
        JButton button = new JButton("Card rules & set updates");
        button.addActionListener(event -> show(owner));
        return button;
    }
    private static void show(Component owner) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), "Card rules & set updates");
        JTextArea text = new JTextArea("Bundled data checked October 8, 2026. Check for newer published card rules, token effects, and sets. Applying future engine changes requires a compatible HOUSE app update.", 16, 56);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setMargin(new Insets(14, 14, 14, 14));
        JButton check = new JButton("Check now"), releases = new JButton("Forge releases"), close = new JButton("Close");
        JPanel actions = new JPanel(); actions.add(check); actions.add(releases); actions.add(close);
        close.addActionListener(e -> dialog.dispose());
        releases.addActionListener(e -> {
            try { Desktop.getDesktop().browse(URI.create(CardDataUpdates.RELEASES)); }
            catch (Exception error) { JOptionPane.showMessageDialog(dialog, "Open " + CardDataUpdates.RELEASES + " in your browser."); }
        });
        check.addActionListener(e -> {
            check.setEnabled(false); text.setText("Checking published Forge card rules, token effects, and set definitions…");
            new SwingWorker<String, Void>() {
                protected String doInBackground() throws Exception {
                    try (InputStream input = ClasspathAssets.openHouseResource("house-card-data.json")) {
                        return CardDataUpdates.check(CardDataUpdates.read(input));
                    }
                }
                protected void done() {
                    if (!dialog.isDisplayable()) return;
                    try { text.setText(get()); }
                    catch (Exception error) { text.setText("Could not check for updates. Your installed card data is unchanged. Please try again."); }
                    text.setCaretPosition(0); check.setEnabled(true);
                }
            }.execute();
        });
        dialog.add(new JScrollPane(text), BorderLayout.CENTER); dialog.add(actions, BorderLayout.SOUTH);
        dialog.pack(); dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
    }
}
