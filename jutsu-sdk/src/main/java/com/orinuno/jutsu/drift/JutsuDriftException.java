package com.orinuno.jutsu.drift;

/**
 * Thrown by parsers running under a strict {@link JutsuParserContext} when an event would otherwise
 * be quietly observed. The exception carries the offending {@link JutsuDriftEvent} so the
 * strict-mode fixture-replay tests can assert <em>which</em> drift signal the parser tripped on,
 * not just that it failed.
 *
 * <p>Production code never instantiates this class — strict mode is reserved for tests and the
 * scheduled canary probe in orinuno-app. Lenient (default) callers see drift events through {@link
 * JutsuDriftDetector} only.
 */
public class JutsuDriftException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final JutsuDriftEvent event;

    public JutsuDriftException(JutsuDriftEvent event) {
        super(formatMessage(event));
        this.event = event;
    }

    public JutsuDriftEvent event() {
        return event;
    }

    private static String formatMessage(JutsuDriftEvent event) {
        if (event == null) return "Jutsu drift event (null)";
        StringBuilder sb = new StringBuilder("Jutsu drift in strict mode: ");
        sb.append(event.signal()).append(" @ ").append(event.source());
        if (event.selector() != null) {
            sb.append(" selector=").append(event.selector());
        }
        if (event.fixtureRef() != null) {
            sb.append(" fixture=").append(event.fixtureRef());
        }
        sb.append(" — ").append(event.detail());
        return sb.toString();
    }
}
