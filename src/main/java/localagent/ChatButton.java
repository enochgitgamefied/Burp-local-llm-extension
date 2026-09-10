package localagent;

import javax.swing.*;
import java.awt.*;

/** Small native control with consistent light/dark styling, independent of Burp's LAF gradients. */
final class ChatButton extends JButton {
    ChatButton(String label) {
        super(label); setContentAreaFilled(false); setOpaque(false); setFocusPainted(true);
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
    @Override protected void paintComponent(Graphics g) {
        Graphics2D gg = (Graphics2D) g.create();
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        ChatView.Palette p = ChatView.Palette.current(); boolean primary = getText().startsWith("Send");
        gg.setColor(primary && isEnabled() ? p.accent() : getModel().isRollover() ? p.line() : p.surface());
        gg.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
        gg.setFont(getFont()); gg.setColor(!isEnabled() ? p.muted() : primary ? p.background() : p.text());
        FontMetrics fm = gg.getFontMetrics(); gg.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
            (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        if (hasFocus()) { gg.setColor(p.muted()); gg.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 12, 12); }
        gg.dispose();
    }
}
