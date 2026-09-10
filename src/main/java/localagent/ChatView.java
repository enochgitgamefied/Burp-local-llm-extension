package localagent;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.*;
import static localagent.ContextPipeline.Message;

/** Native Swing conversation layout; model text never becomes executable HTML. */
final class ChatView extends JPanel {
    record Palette(Color background, Color surface, Color text, Color muted, Color line, Color accent) {
        static Palette current() {
            Color bg = UIManager.getColor("Panel.background");
            boolean dark = bg != null && bg.getRed() + bg.getGreen() + bg.getBlue() < 380;
            return dark ? new Palette(new Color(0x202124), new Color(0x2D2F33), new Color(0xECEDEF), new Color(0xACAFB7), new Color(0x414349), new Color(0xDCE2E8))
                : new Palette(new Color(0xFFFFFF), new Color(0xF3F4F6), new Color(0x24272C), new Color(0x747B85), new Color(0xE5E7EB), new Color(0x24272C));
        }
    }
    private Palette palette = Palette.current();
    private final Timeline timeline = new Timeline();
    private final JScrollPane scroll = new JScrollPane(timeline);
    private List<Message> messages = List.of();
    private String pending;
    private boolean scrollToBottom;
    private String progress = "Thinking…";

    ChatView() {
        super(new BorderLayout());
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(22);
        add(scroll); ChatScroll.style(scroll);
        refreshTheme();
    }
    void refreshTheme() {
        palette = Palette.current(); setBackground(palette.background());
        scroll.getViewport().setBackground(palette.background()); timeline.setBackground(palette.background()); ChatScroll.style(scroll); rebuild(false);
    }
    void showMessages(List<Message> value) { messages = List.copyOf(value); pending = null; rebuild(true); }
    void showPending(String prompt) { pending = prompt; progress = "Thinking…"; rebuild(true); }
    void clearPending() { pending = null; rebuild(false); }
    void setProgress(String value) {
        progress = value;
        if (pending != null && timeline.getComponentCount() > 0) {
            Component last = timeline.getComponent(timeline.getComponentCount() - 1);
            if (last instanceof MessageCard card) card.setProgress(value);
        }
    }
    private void rebuild(boolean bottom) {
        timeline.removeAll();
        for (Message message : messages) timeline.add(new MessageCard(message.role(), message.content(), false));
        if (pending != null) { timeline.add(new MessageCard("user", pending, false)); timeline.add(new MessageCard("assistant", progress, true)); }
        scrollToBottom = bottom; timeline.revalidate(); timeline.repaint();
    }
    private final class Timeline extends JPanel implements Scrollable {
        Timeline() { setLayout(null); }
        int contentWidth() { return Math.max(180, Math.min(820, getWidth() - (getWidth() < 600 ? 32 : 80))); }
        private int position(boolean apply) {
            int width = contentWidth(), y = 30;
            for (Component c : getComponents()) {
                MessageCard card = (MessageCard) c;
                int cardWidth = card.user ? Math.min(width, Math.max(200, (int) (width * .82))) : width;
                int height = card.heightFor(cardWidth);
                if (apply) card.setBounds((getWidth() - width) / 2 + (card.user ? width - cardWidth : 0), y, cardWidth, height);
                y += height + (card.user ? 26 : 38);
            }
            return y + 24;
        }
        @Override public void doLayout() {
            position(true);
            if (scrollToBottom && getParent() instanceof JViewport viewport) {
                scrollToBottom = false;
                viewport.setViewPosition(new Point(0, Math.max(0, getPreferredSize().height - viewport.getExtentSize().height)));
            }
        }
        @Override public Dimension getPreferredSize() { return new Dimension(300, getComponentCount() == 0 ? 360 : position(false)); }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(800, 500); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getComponentCount() == 0 || getParent() != null && getPreferredSize().height < getParent().getHeight(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(24, r.height - 48); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getComponentCount() != 0) return;
            Graphics2D gg = (Graphics2D) g.create(); gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int middle = getWidth() / 2, y = Math.max(70, getHeight() / 2 - 65);
            gg.setColor(palette.surface()); gg.fillRoundRect(middle - 24, y - 52, 48, 48, 18, 18);
            gg.setColor(palette.text()); gg.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22)); center(gg, "⌘", middle, y - 20);
            gg.setFont(new Font(Font.SANS_SERIF, Font.BOLD, getWidth() < 520 ? 23 : 29)); center(gg, "What would you like to review?", middle, y + 36);
            gg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14)); gg.setColor(palette.muted());
            center(gg, "Ask a question or add an HTTP exchange from Burp.", middle, y + 69);
            center(gg, "Your conversation stays with your local model.", middle, y + 93);
            gg.dispose();
        }
    }
    private static void center(Graphics2D g, String text, int x, int y) { g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, y); }
    private final class MessageCard extends JPanel {
        final boolean user;
        final boolean thinking;
        final String raw;
        final JEditorPane body = new JEditorPane();
        final JLabel author = new JLabel();
        final JButton copy = new JButton("Copy");
        final JButton expand = new JButton("Show full message");
        boolean collapsed;
        MessageCard(String role, String content, boolean thinking) {
            setLayout(null); setOpaque(false); user = role.equals("user"); raw = content; this.thinking = thinking;
            collapsed = content.length() > 2200;
            body.setEditorKit(new WrappingHtmlKit()); body.setEditable(false); body.setOpaque(false);
            body.setBorder(BorderFactory.createEmptyBorder()); body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
            body.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15)); body.setForeground(palette.text());
            // Only escaped, locally generated markup is rendered. No images or active links.
            author.setText(user ? "You" : thinking ? "Local Agent  ·  Working" : "Local Agent");
            author.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12)); author.setForeground(palette.muted());
            configure(copy); configure(expand);
            copy.setToolTipText("Copy the original message");
            copy.addActionListener(e -> { try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(raw), null);
                copy.setText("Copied"); javax.swing.Timer timer = new javax.swing.Timer(1500, event -> copy.setText("Copy")); timer.setRepeats(false); timer.start();
            } catch (IllegalStateException | HeadlessException ex) { copy.setText("Copy unavailable"); } });
            expand.addActionListener(e -> { collapsed = !collapsed; updateBody(); timeline.revalidate(); timeline.repaint(); });
            add(author); add(body); if (!thinking) add(copy); if (raw.length() > 2200) add(expand);
            updateBody();
        }
        private void configure(JButton button) {
            button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); button.setForeground(palette.muted()); button.setContentAreaFilled(false);
            button.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0)); button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        void setProgress(String value) { body.setText(document(Markdown.inline(value), palette)); }
        private void updateBody() {
            String shown = collapsed ? raw.substring(0, 1800) + "\n\n…" : raw;
            body.setText(document(user ? "<p>" + Markdown.escape(shown).replace("\n", "<br>") + "</p>" : Markdown.render(shown), palette));
            body.setCaretPosition(0); expand.setText(collapsed ? "Show full message" : "Collapse message");
        }
        int heightFor(int width) {
            body.setSize(Math.max(80, width - (user ? 36 : 4)), Short.MAX_VALUE);
            return body.getPreferredSize().height + (thinking ? 42 : 76);
        }
        @Override public void doLayout() {
            int pad = user ? 18 : 2;
            author.setBounds(pad, 12, getWidth() - pad * 2, 18);
            int bodyHeight = Math.max(10, getHeight() - (thinking ? 42 : 76));
            body.setBounds(pad, 38, Math.max(80, getWidth() - pad * 2), bodyHeight);
            copy.setBounds(pad, getHeight() - 29, 90, 22);
            expand.setBounds(Math.max(pad + 95, getWidth() - 165), getHeight() - 29, 145, 22);
        }
        @Override protected void paintComponent(Graphics g) {
            if (user) { Graphics2D gg = (Graphics2D) g.create(); gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gg.setColor(palette.surface()); gg.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22); gg.dispose(); }
            super.paintComponent(g);
        }
    }
    /** Allow long URLs and HTTP/code lines to wrap instead of disappearing past the card edge. */
    private static final class WrappingHtmlKit extends HTMLEditorKit {
        private final ViewFactory factory = new HTMLFactory() {
            @Override public View create(Element element) {
                View view = super.create(element);
                if (view instanceof InlineView) return new InlineView(element) {
                    @Override public float getMinimumSpan(int axis) { return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis); }
                    @Override public int getBreakWeight(int axis, float pos, float len) {
                        return axis == View.X_AXIS ? GoodBreakWeight : super.getBreakWeight(axis, pos, len);
                    }
                    @Override public View breakView(int axis, int offset, float pos, float len) {
                        if (axis != View.X_AXIS) return super.breakView(axis, offset, pos, len);
                        checkPainter();
                        int end = getGlyphPainter().getBoundedPosition(this, offset, pos, len);
                        return createFragment(offset, Math.max(offset + 1, end));
                    }
                };
                return view;
            }
        };
        @Override public ViewFactory getViewFactory() { return factory; }
    }
    private static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }
    static String document(String body, Palette p) {
        return "<html><head><style>body {font-family: sans-serif; font-size: 15pt; color:" + hex(p.text()) + "; margin:0;} "
            + "p {margin-top:4px; margin-bottom:12px;} h1 {font-size:23pt; margin-top:12px; margin-bottom:10px;} "
            + "h2 {font-size:19pt; margin-top:16px; margin-bottom:8px;} h3 {font-size:16pt; margin-top:14px; margin-bottom:7px;} "
            + "ul,ol {margin-left:20px; margin-top:5px; margin-bottom:12px;} li {margin-bottom:7px;} "
            + "p.codeblock {font-family:monospace; font-size:12pt; padding:12px; background:" + hex(p.surface()) + ";} "
            + "code {font-family:monospace; font-size:12pt;} table {border-collapse:collapse; margin-top:10px; margin-bottom:14px;} "
            + "td,th {padding:9px; border:1px solid " + hex(p.line()) + ";} th {background:" + hex(p.surface()) + "; text-align:left;} "
            + "blockquote {margin-left:12px; color:" + hex(p.muted()) + ";} hr {color:" + hex(p.line()) + ";}</style></head><body>" + body + "</body></html>";
    }
}
