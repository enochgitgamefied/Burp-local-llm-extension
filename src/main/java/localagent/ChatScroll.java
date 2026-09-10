package localagent;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;

final class ChatScroll {
    private ChatScroll() {}
    static void style(JScrollPane scroll) {
        JScrollBar bar = scroll.getVerticalScrollBar(); bar.setPreferredSize(new Dimension(10, 0));
        bar.setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return invisible(); }
            @Override protected JButton createIncreaseButton(int orientation) { return invisible(); }
            private JButton invisible() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
                g.setColor(ChatView.Palette.current().background()); g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
                Graphics2D gg = (Graphics2D) g.create(); gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gg.setColor(ChatView.Palette.current().line()); gg.fillRoundRect(bounds.x + 2, bounds.y, Math.max(3, bounds.width - 4), bounds.height, 8, 8); gg.dispose();
            }
        });
    }
}
