package localagent;

import burp.api.montoya.MontoyaApi;
import com.google.gson.*;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import static localagent.ContextPipeline.*;

final class AgentPanel extends JPanel {
    static final class QueueItem {
        final String title;
        String draft;
        QueueItem(String title, String draft) { this.title = title; this.draft = draft; }
        @Override public String toString() { return title; }
    }
    private final MontoyaApi api;
    final DefaultListModel<QueueItem> queue = new DefaultListModel<>();
    final JList<QueueItem> queueList = new JList<>(queue);
    final JTextArea draft = area(true);
    final ChatView conversation = new ChatView();
    private final JPanel queuePanel = new JPanel(new BorderLayout(10, 10));
    private final JButton queueToggle = new ChatButton("Queue  0");
    private final JLabel modelLabel = new JLabel();
    private final JLabel titleLabel = new JLabel("Local Agent"), queueHeading = new JLabel("REVIEW QUEUE");
    private final JLabel draftLabel = new JLabel("Message Local Agent");
    private JPanel chat, composer, header, footer;
    private final JTextField base = new JTextField("http://localhost:11434/v1", 36);
    private final JComboBox<String> model = new JComboBox<>();
    private final JPasswordField key = new JPasswordField(24);
    private final JSpinner context = new JSpinner(new SpinnerNumberModel(16384, 2048, 262144, 1024));
    private final JSpinner output = new JSpinner(new SpinnerNumberModel(2048, 128, 131072, 128));
    private final JSpinner responseTimeout = new JSpinner(new SpinnerNumberModel(600, 1, 3600, 30));
    private final JCheckBox redact = new JCheckBox("Redact credential and cookie headers when queueing", true);
    private final JLabel status = new JLabel("Local model · Ready");
    private final JLabel estimate = new JLabel("Enter to send · Shift+Enter for a new line");
    private final JButton send = new ChatButton("Send"), cancel = new ChatButton("Stop"), test = new ChatButton("Test connection / list models");
    private final JButton fresh = new ChatButton("New chat"), remove = new ChatButton("Remove selected"), export = new ChatButton("Export chat…"), reopen = new ChatButton("Open chat…");
    private final List<Message> history = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "burp-local-agent"); t.setDaemon(true); return t; });
    private volatile LocalClient client;
    private Future<?> task;
    private long generation;
    private boolean changingDraft;
    private QueueItem editing;
    private String submittedDraft;
    private final JTabbedPane tabs = new JTabbedPane();

    AgentPanel(MontoyaApi api) {
        super(new BorderLayout(8, 8)); this.api = api;
        setBorder(BorderFactory.createEmptyBorder());
        model.setEditable(true); model.addItem("llama3.1");
        loadSettings();
        queueList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        queueList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            editing = queueList.getSelectedValue();
            if (editing != null) setDraft(editing.draft);
            draftLabel.setText(editing == null ? "Message Local Agent" : "Review captured exchange");
        });
        draft.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!changingDraft && editing != null) editing.draft = draft.getText();
                estimate.setText(draft.getText().isBlank() ? "Enter to send · Shift+Enter for a new line" : "~" + ContextPipeline.estimate(draft.getText()) + " tokens · Enter to send");
                send.setEnabled(task == null && !draft.getText().isBlank());
            }
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        });
        queuePanel.setPreferredSize(new Dimension(260, 400));
        queuePanel.setBorder(BorderFactory.createEmptyBorder(22, 18, 18, 16));
        queueHeading.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        queuePanel.add(queueHeading, BorderLayout.NORTH);
        JScrollPane queueScroll = new JScrollPane(queueList); queueScroll.setBorder(BorderFactory.createEmptyBorder());
        ChatScroll.style(queueScroll); queuePanel.add(queueScroll); queuePanel.add(remove, BorderLayout.SOUTH); queuePanel.setVisible(false);
        queueList.setFixedCellHeight(66);
        queueList.setCellRenderer((list, value, index, selected, focus) -> {
            ChatView.Palette colors = ChatView.Palette.current();
            JLabel cell = new JLabel("<html><b>" + Markdown.escape(value.title.split(" ", 2)[0]) + "</b><br>"
                + Markdown.escape(value.title.length() > 37 ? value.title.substring(0, 34) + "…" : value.title) + "</html>");
            cell.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10)); cell.setOpaque(true);
            cell.setBackground(selected ? colors.line() : colors.surface()); cell.setForeground(colors.text());
            cell.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); return cell;
        });
        queue.addListDataListener(new ListDataListener() {
            private void update() { queueToggle.setText("Queue  " + queue.size()); if (queue.isEmpty()) queuePanel.setVisible(false); revalidate(); }
            public void intervalAdded(ListDataEvent e) { update(); }
            public void intervalRemoved(ListDataEvent e) { update(); }
            public void contentsChanged(ListDataEvent e) { update(); }
        });
        chat = new JPanel(new BorderLayout());
        header = new JPanel(new BorderLayout(12, 0)); header.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
        JPanel identity = transparent(new BorderLayout(0, 5));
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        modelLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        identity.add(titleLabel, BorderLayout.NORTH); identity.add(modelLabel, BorderLayout.SOUTH); header.add(identity, BorderLayout.WEST);
        JPanel toolbar = transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JButton more = new ChatButton("•••"); more.setToolTipText("Import and export chat");
        JPopupMenu menu = new JPopupMenu();
        JMenuItem exportItem = new JMenuItem("Export chat…"), openItem = new JMenuItem("Open chat…");
        exportItem.addActionListener(e -> exportChat()); openItem.addActionListener(e -> { if (task == null) openChat(); });
        menu.add(exportItem); menu.add(openItem); more.addActionListener(e -> menu.show(more, 0, more.getHeight()));
        toolbar.add(queueToggle); toolbar.add(fresh); toolbar.add(more); header.add(toolbar, BorderLayout.EAST);
        queueToggle.addActionListener(e -> { queuePanel.setVisible(!queuePanel.isVisible()); chat.revalidate(); });
        model.addActionListener(e -> modelLabel.setText(String.valueOf(model.getSelectedItem()) + "  ·  Local"));
        modelLabel.setText(String.valueOf(model.getSelectedItem()) + "  ·  Local");
        draft.setRows(3); draft.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        draft.setBorder(BorderFactory.createEmptyBorder(4, 2, 8, 2));
        JScrollPane draftScroll = new JScrollPane(draft); ChatScroll.style(draftScroll); draftScroll.setBorder(BorderFactory.createEmptyBorder());
        composer = new JPanel(new BorderLayout(0, 8)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D gg = (Graphics2D) g.create(); gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ChatView.Palette colors = ChatView.Palette.current(); gg.setColor(colors.surface());
                gg.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 26, 26); gg.setColor(colors.line());
                gg.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 26, 26); gg.dispose(); super.paintComponent(g);
            }
        };
        composer.setOpaque(false); composer.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 14));
        draftLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12)); composer.add(draftLabel, BorderLayout.NORTH); composer.add(draftScroll);
        JPanel actions = transparent(new BorderLayout()); estimate.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        actions.add(estimate, BorderLayout.WEST); JPanel controls = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.add(cancel); controls.add(send); actions.add(controls, BorderLayout.EAST); composer.add(actions, BorderLayout.SOUTH);
        footer = transparent(new BorderLayout(0, 8)); footer.add(composer);
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11)); status.setHorizontalAlignment(SwingConstants.CENTER);
        status.setPreferredSize(new Dimension(100, 22)); footer.add(status, BorderLayout.SOUTH);
        JPanel centeredFooter = new JPanel(null) {
            @Override public void doLayout() { int width = Math.max(180, Math.min(820, getWidth() - (getWidth() < 600 ? 32 : 80)));
                footer.setBounds((getWidth() - width)/2, 0, width, getHeight() - 12); }
            @Override public Dimension getPreferredSize() { return new Dimension(300, 210); }
        };
        centeredFooter.setOpaque(false); centeredFooter.add(footer);
        JPanel center = transparent(new BorderLayout()); center.add(conversation); center.add(centeredFooter, BorderLayout.SOUTH);
        chat.add(header, BorderLayout.NORTH); chat.add(queuePanel, BorderLayout.WEST); chat.add(center);
        tabs.addTab("Agent", chat); tabs.addTab("Settings", settingsPanel()); add(tabs);
        for (JButton button : new JButton[]{send, cancel, fresh, queueToggle, more, remove}) {
            button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); button.setFocusPainted(true);
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); button.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
        }
        send.setText("Send ↑"); send.setToolTipText("Send message (Enter)"); send.setEnabled(false);
        cancel.setEnabled(false); cancel.setVisible(false);
        draft.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        draft.getActionMap().put("send-message", new AbstractAction() { public void actionPerformed(ActionEvent e) { send(); } });
        draft.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        applyChatTheme();
        send.addActionListener(e -> send()); cancel.addActionListener(e -> cancel());
        remove.addActionListener(e -> { int index = queueList.getSelectedIndex(); if (index >= 0) { editing = null; queue.remove(index); if (queue.isEmpty()) setDraft(""); } });
        fresh.addActionListener(e -> { if (history.isEmpty() || JOptionPane.showConfirmDialog(this,
            "Clear this chat? Export it first if you want to reopen it later.", "New chat", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            history.clear(); renderHistory(); queueList.clearSelection(); editing = null; setDraft(""); } });
        export.addActionListener(e -> exportChat()); reopen.addActionListener(e -> openChat());
        test.addActionListener(e -> testConnection());
    }
    private static JPanel transparent(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    void applyChatTheme() {
        ChatView.Palette p = ChatView.Palette.current();
        titleLabel.setForeground(p.text()); queueHeading.setForeground(p.muted());
        chat.setBackground(p.background()); header.setBackground(p.background()); queuePanel.setBackground(p.surface());
        queueList.setBackground(p.surface()); draft.setBackground(p.surface()); draft.setForeground(p.text()); draft.setCaretColor(p.text());
        modelLabel.setForeground(p.muted()); estimate.setForeground(p.muted()); status.setForeground(p.muted()); draftLabel.setForeground(p.muted());
        send.setBackground(p.accent()); send.setForeground(p.background()); send.setOpaque(true); send.setContentAreaFilled(true);
        conversation.refreshTheme();
    }
    private static JTextArea area(boolean editable) {
        JTextArea area = new JTextArea(); area.setEditable(editable); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); return area;
    }
    private JPanel settingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(7, 7, 7, 7);
        JComboBox<String> preset = new JComboBox<>(new String[]{"Custom", "Ollama", "LM Studio"});
        preset.addActionListener(e -> {
            if (preset.getSelectedIndex() == 1) base.setText("http://localhost:11434/v1");
            if (preset.getSelectedIndex() == 2) base.setText("http://localhost:1234/v1");
        });
        Object[][] rows = {{"Provider preset", preset}, {"Local base URL", base}, {"Chat model", model},
            {"API key (memory only)", key}, {"Context token budget", context}, {"Output token limit", output}, {"Model response timeout (seconds)", responseTimeout}};
        for (Object[] row : rows) { c.gridx = 0; panel.add(new JLabel((String) row[0]), c); c.gridx = 1; panel.add((Component) row[1], c); c.gridy++; }
        c.gridx = 0; c.gridwidth = 2; panel.add(redact, c); c.gridy++;
        panel.add(new JLabel("Header redaction does not remove secrets from URLs, bodies, or custom headers. Review the prompt."), c); c.gridy++;
        JButton save = new ChatButton("Save settings");
        save.addActionListener(e -> { try {
            LocalClient.Settings s = settings();
            api.persistence().preferences().setString("burp-local-agent.settings", new Gson().toJson(Map.of(
                "baseUrl", s.baseUrl(), "model", s.model(), "context", s.contextTokens(), "output", s.outputTokens(), "redact", redact.isSelected(), "responseTimeoutSeconds", s.responseTimeoutSeconds())));
            status.setText("Settings saved. API key is kept only for this extension session.");
        } catch (Exception ex) { error(ex); } });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT)); buttons.add(save); buttons.add(test); panel.add(buttons, c); c.gridy++;
        JLabel settingsStatus = new JLabel("Configure your local model, then test the connection.");
        status.addPropertyChangeListener("text", e -> settingsStatus.setText(status.getText()));
        c.weighty = 1; panel.add(settingsStatus, c);
        return panel;
    }
    private void loadSettings() {
        try {
            String saved = api.persistence().preferences().getString("burp-local-agent.settings");
            if (saved == null) return;
            JsonObject s = JsonParser.parseString(saved).getAsJsonObject();
            base.setText(s.get("baseUrl").getAsString()); model.setSelectedItem(s.get("model").getAsString());
            responseTimeout.setValue(s.has("responseTimeoutSeconds") ? s.get("responseTimeoutSeconds").getAsInt() : 600);
            context.setValue(s.get("context").getAsInt()); output.setValue(s.get("output").getAsInt()); redact.setSelected(s.get("redact").getAsBoolean());
        } catch (Exception e) { status.setText("Saved settings could not be loaded; check Settings."); }
    }
    private LocalClient.Settings settings() {
        return new LocalClient.Settings(base.getText(), String.valueOf(model.getSelectedItem()), new String(key.getPassword()),
            ((Number) context.getValue()).intValue(), ((Number) output.getValue()).intValue(), ((Number) responseTimeout.getValue()).intValue());
    }
    boolean redactHeaders() { return redact.isSelected(); }
    void enqueue(String title, String exchange) {
        if (queue.size() >= 200) { status.setText("Queue is full (200 exchanges). Remove items before adding more."); return; }
        QueueItem item = new QueueItem(title, INSTRUCTION + "\n\n" + preprocess(exchange, redactHeaders()));
        queue.addElement(item); queuePanel.setVisible(true); chat.revalidate();
        if (editing == null && draft.getText().isBlank() && task == null) queueList.setSelectedValue(item, true);
        tabs.setSelectedIndex(0); status.setText("Queued " + queue.size() + " exchange(s). Open Burp-Local-Agent to review and send.");
    }
    private void setDraft(String value) { changingDraft = true; draft.setText(value); draft.setCaretPosition(0); changingDraft = false; }
    private void busy(boolean busy) {
        send.setEnabled(!busy && !draft.getText().isBlank()); test.setEnabled(!busy); fresh.setEnabled(!busy); reopen.setEnabled(!busy);
        remove.setEnabled(!busy); queueList.setEnabled(!busy); draft.setEditable(!busy); cancel.setEnabled(busy); cancel.setVisible(busy); send.setVisible(!busy);
        tabs.setEnabledAt(1, !busy);
        draftLabel.setText(busy && submittedDraft != null ? "Message sent" : editing == null ? "Message Local Agent" : "Review captured exchange");
        estimate.setText(busy ? "Waiting for response…" : draft.getText().isBlank() ? "Enter to send · Shift+Enter for a new line"
            : "~" + ContextPipeline.estimate(draft.getText()) + " tokens · Enter to send");
    }
    private void send() {
        String prompt = draft.getText(); if (prompt.isBlank() || task != null) return;
        try {
            LocalClient current = new LocalClient(settings()); client = current;
            long run = ++generation;
            QueueItem item = editing;
            List<Message> previous = List.copyOf(history);
            submittedDraft = prompt;
            setDraft("");
            busy(true); conversation.showPending(prompt); status.setText("Preparing local review…");
            task = executor.submit(() -> {
                try {
                    String answer = current.analyze(previous, prompt, update -> onEdt(run, () -> { status.setText(update); conversation.setProgress(update); }));
                    onEdt(run, () -> {
                        submittedDraft = null;
                        history.add(new Message("user", prompt)); history.add(new Message("assistant", answer));
                        renderHistory(); editing = null; queueList.clearSelection(); if (item != null) queue.removeElement(item);
                        setDraft(""); status.setText("Review complete. Enter a follow-up or select another queued exchange.");
                    });
                } catch (Exception ex) { onEdt(run, () -> { conversation.clearPending(); restoreSubmittedDraft(); error(ex); }); }
                finally { current.close(); onEdt(run, () -> { task = null; client = null; busy(false); }); }
            });
        } catch (Exception ex) {
            restoreSubmittedDraft(); conversation.clearPending();
            if (client != null) client.close(); client = null; task = null; busy(false); error(ex);
        }
    }
    private void testConnection() {
        try {
            LocalClient current = new LocalClient(settings()); client = current; long run = ++generation;
            busy(true); status.setText("Connecting to local model server…");
            task = executor.submit(() -> {
                try {
                    List<String> ids = current.models();
                    onEdt(run, () -> {
                        Object selected = model.getSelectedItem(); model.removeAllItems(); ids.forEach(model::addItem);
                        if (ids.contains(selected)) model.setSelectedItem(selected);
                        else if (ids.isEmpty()) model.setSelectedItem(selected);
                        status.setText(ids.isEmpty() ? "Connected; no chat models found. Load a model on the server." : "Connected. Found " + ids.size() + " chat model(s).");
                    });
                } catch (Exception ex) { onEdt(run, () -> { conversation.clearPending(); restoreSubmittedDraft(); error(ex); }); }
                finally { current.close(); onEdt(run, () -> { task = null; client = null; busy(false); }); }
            });
        } catch (Exception ex) { error(ex); }
    }
    private void onEdt(long run, Runnable action) { SwingUtilities.invokeLater(() -> { if (generation == run) action.run(); }); }
    private void cancel() {
        ++generation;
        LocalClient current = client; client = null;
        if (task != null) task.cancel(true); task = null;
        if (current != null) { Thread closer = new Thread(current::close, "burp-local-agent-cancel"); closer.setDaemon(true); closer.start(); }
        conversation.clearPending(); restoreSubmittedDraft(); busy(false); status.setText("Cancelled. The draft remains available to edit or retry.");
    }
    private void restoreSubmittedDraft() {
        if (submittedDraft != null) { setDraft(submittedDraft); submittedDraft = null; }
    }
    void shutdown() { cancel(); executor.shutdownNow(); key.setText(""); }
    private void renderHistory() {
        conversation.showMessages(history);
    }

    private void exportChat() {
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("burp-local-agent-chat.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace the selected file?", "Export chat", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try { Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(history), StandardCharsets.UTF_8);
            status.setText("Chat exported. It contains the prompts and responses shown in this tab."); } catch (Exception ex) { error(ex); }
    }
    private void openChat() {
        JFileChooser chooser = new JFileChooser(); if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path path = chooser.getSelectedFile().toPath();
            if (Files.size(path) > 8 * 1024 * 1024) throw new IllegalArgumentException("Chat file exceeds 8 MiB.");
            Message[] loaded = new Gson().fromJson(Files.readString(path), Message[].class);
            if (loaded == null || loaded.length > 1000) throw new IllegalArgumentException("Invalid or oversized chat history.");
            for (int i = 0; i < loaded.length; i++) {
                Message m = loaded[i];
                if (m == null || !Objects.equals(m.role(), i % 2 == 0 ? "user" : "assistant") || m.content() == null)
                    throw new IllegalArgumentException("Chat must contain alternating user and assistant messages.");
            }
            if (loaded.length % 2 != 0) throw new IllegalArgumentException("Chat contains an unfinished turn.");
            if (!history.isEmpty() && JOptionPane.showConfirmDialog(this, "Replace the current chat? Export it first to keep it.",
                "Open chat", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            history.clear(); history.addAll(Arrays.asList(loaded)); renderHistory(); status.setText("Chat opened. Enter a follow-up to continue.");
        } catch (Exception ex) { error(ex); }
    }
    private void error(Exception e) {
        String message = e.getMessage();
        status.setText("Error: " + (message == null ? e.getClass().getSimpleName() : message));
        api.logging().logToError("Burp-Local-Agent: " + e.getClass().getSimpleName());
    }
}
