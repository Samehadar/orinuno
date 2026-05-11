package com.orinuno.cvh.host;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Ordered list of {@link CvhHostPageParser}s consulted by the pipeline. First match wins. The
 * registry is immutable after construction — extension happens through {@link
 * com.orinuno.cvh.CvhClient.Builder#registerHost} which builds a new registry per client.
 */
public final class CvhHostRegistry {

    private final List<CvhHostPageParser> hosts;

    public CvhHostRegistry(List<CvhHostPageParser> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("hosts must contain at least one parser");
        }
        this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
    }

    public List<CvhHostPageParser> hosts() {
        return hosts;
    }

    public Optional<CvhHostPageParser> resolve(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(pageUrl);
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
        return resolve(uri);
    }

    public Optional<CvhHostPageParser> resolve(URI pageUrl) {
        if (pageUrl == null) {
            return Optional.empty();
        }
        for (CvhHostPageParser host : hosts) {
            if (host.supports(pageUrl)) {
                return Optional.of(host);
            }
        }
        return Optional.empty();
    }
}
