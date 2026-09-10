package localagent;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.*;
import javax.swing.*;
import java.awt.Component;
import java.util.List;

public final class BurpLocalAgent implements BurpExtension {
    private AgentPanel panel;
    @Override public void initialize(MontoyaApi api) {
        api.extension().setName("Burp-Local-Agent");
        Runnable setup = () -> {
            panel = new AgentPanel(api);
            api.userInterface().applyThemeToComponent(panel);
            panel.applyChatTheme();
            api.userInterface().registerSuiteTab("Burp-Local-Agent", panel);
            api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
                @Override public List<Component> provideMenuItems(ContextMenuEvent event) {
                    List<HttpRequestResponse> selected = event.selectedRequestResponses();
                    if (selected.isEmpty() && event.messageEditorRequestResponse().isPresent())
                        selected = List.of(event.messageEditorRequestResponse().get().requestResponse());
                    if (selected.isEmpty()) return List.of();
                    List<HttpRequestResponse> captures = List.copyOf(selected);
                    JMenuItem send = new JMenuItem("Send to Burp-Local-Agent (" + captures.size() + ")");
                    send.addActionListener(action -> {
                        for (HttpRequestResponse capture : captures) {
                            if (capture.request() == null) continue;
                            if (capture.request().toByteArray().length() + (capture.response() == null ? 0L : capture.response().toByteArray().length()) > 2 * 1024 * 1024) {
                                JOptionPane.showMessageDialog(panel, "An exchange exceeds 2 MiB. Select a smaller capture or paste a relevant excerpt into the Agent prompt.");
                                continue;
                            }
                            String title = capture.request().method() + " " + capture.request().url();
                            String exchange = "--- REQUEST ---\n" + capture.request().toString()
                                + (capture.response() == null ? "\n[No captured response]" : "\n--- RESPONSE ---\n" + capture.response().toString());
                            panel.enqueue(title, exchange);
                        }
                    });
                    return List.of(send);
                }
            });
            api.extension().registerUnloadingHandler(() -> {
                if (SwingUtilities.isEventDispatchThread()) panel.shutdown(); else SwingUtilities.invokeLater(panel::shutdown);
            });
            api.logging().logToOutput("Burp-Local-Agent loaded. Configure the local model in its Settings tab.");
        };
        try { if (SwingUtilities.isEventDispatchThread()) setup.run(); else SwingUtilities.invokeAndWait(setup); }
        catch (Exception e) { api.logging().logToError("Burp-Local-Agent initialization failed: " + e.getClass().getSimpleName()); throw new IllegalStateException(e); }
    }
}
