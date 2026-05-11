package com.orinuno.aksor.host;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Ordered list of {@link AksorHostPageParser}s consulted by the pipeline. First match wins.
 * Immutable after construction; extend through {@link com.orinuno.aksor.AksorClient.Builder}.
 */
public final class AksorHostRegistry {

    private final List<AksorHostPageParser> hosts;

    public AksorHostRegistry(List<AksorHostPageParser> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("hosts must contain at least one parser");
        }
        this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
    }

    public List<AksorHostPageParser> hosts() {
        return hosts;
    }

    public Optional<AksorHostPageParser> resolve(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            return resolve(new URI(pageUrl));
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
    }

    public Optional<AksorHostPageParser> resolve(URI pageUrl) {
        if (pageUrl == null) {
            return Optional.empty();
        }
        for (AksorHostPageParser h : hosts) {
            if (h.supports(pageUrl)) {
                return Optional.of(h);
            }
        }
        return Optional.empty();
    }
}
