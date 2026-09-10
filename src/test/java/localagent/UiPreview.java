package localagent;

import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import static localagent.ContextPipeline.Message;

/** Synthetic local fixtures for visual QA; never contacts a model or target. */
public final class UiPreview {
    static AgentPanel panel;
    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        UIManager.put("Panel.background", Color.WHITE);
        SwingUtilities.invokeAndWait(() -> {
            MontoyaApi api = (MontoyaApi) AgentTests.proxy(MontoyaApi.class, (method, arguments) -> AgentTests.proxy(method.getReturnType(), (m, a) -> {
                if (m.getName().equals("preferences")) return AgentTests.proxy(m.getReturnType(), (pm, pa) -> null);
                return null;
            }));
            panel = new AgentPanel(api);
            panel.setSize(1440, 1200);
            snapshot("ui-empty.png");
            panel.conversation.showMessages(List.of(
                new Message("user", "Review this response. What should I improve before shipping?"),
                new Message("assistant", "## Response review\nThe response has a solid starting point. I found **two improvements** supported by the captured headers.\n\n### Observations\n| Header | Observation |\n| --- | --- |\n| Content-Type | JSON is declared correctly |\n| Cache-Control | No cache policy is present |\n| Set-Cookie | Secure and HttpOnly are present |\n\n### Recommended changes\n- Set an explicit **cache policy** for responses containing account data.\n- Set **SameSite** explicitly to document the intended cookie behavior.\n\n```http\nCache-Control: no-store\nSet-Cookie: session=[redacted]; Secure; HttpOnly; SameSite=Lax\n```\n\nThese observations are based on the supplied capture. The server’s broader configuration has not been verified.")));
            panel.draft.setText("Explain the caching recommendation");
        });
        SwingUtilities.invokeAndWait(() -> snapshot("ui-conversation.png"));
        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(820, 980); snapshot("ui-narrow.png");
            UIManager.put("Panel.background", new Color(0x202124)); panel.applyChatTheme(); panel.setSize(1440, 1200);
        });
        SwingUtilities.invokeAndWait(() -> snapshot("ui-dark.png"));
        SwingUtilities.invokeAndWait(() -> {
            panel.conversation.showPending("Summarize the captured headers.");
            panel.conversation.setProgress("Reviewing chunk 2 of 4…");
        });
        SwingUtilities.invokeAndWait(() -> {
            snapshot("ui-working.png");
            panel.setSize(820, 980);
            panel.conversation.showMessages(List.of(new Message("assistant", "## Long header preview\n```http\nX-Diagnostic: " + "abcdef".repeat(160) + "\n```\nEnd of captured header.")));
        });
        SwingUtilities.invokeAndWait(() -> { snapshot("ui-long-header.png"); panel.shutdown(); });
    }
    static void snapshot(String file) {
        AgentTests.layout(panel); AgentTests.layout(panel);
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
        try { javax.imageio.ImageIO.write(image, "png", new File("work/" + file)); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
}
