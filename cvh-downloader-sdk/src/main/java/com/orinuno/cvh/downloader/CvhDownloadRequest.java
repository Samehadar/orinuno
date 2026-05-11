package com.orinuno.cvh.downloader;

import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.candidate.QualityPreference;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Per-call inputs for {@link CvhDownloader#download}.
 *
 * <ul>
 *   <li>{@code filenameHint} — base name (without extension); sanitized by {@code
 *       FilenameSanitizer}. The container extension is appended by the downloader.
 *   <li>{@code referer} — value of the {@code Referer} header for all CDN/segment GETs. Must match
 *       the publisher whitelist on the CVH side (e.g. {@code https://jut-su.works/}).
 *   <li>{@code preference} — ordering policy used to build the default candidate chain.
 *   <li>{@code customChain} — if non-null, overrides {@code preference} entirely; useful when the
 *       caller has its own quality strategy (e.g. UI-driven per-user choice).
 * </ul>
 */
public record CvhDownloadRequest(
        String filenameHint,
        String referer,
        QualityPreference preference,
        @Nullable List<DownloadCandidate> customChain) {

    public CvhDownloadRequest {
        if (filenameHint == null || filenameHint.isBlank()) {
            throw new IllegalArgumentException("filenameHint must not be blank");
        }
        if (referer == null || referer.isBlank()) {
            throw new IllegalArgumentException("referer must not be blank");
        }
        if (preference == null) {
            preference = QualityPreference.BEST_FIRST;
        }
        customChain = customChain == null ? null : List.copyOf(customChain);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String filenameHint;
        private String referer;
        private QualityPreference preference = QualityPreference.BEST_FIRST;
        @Nullable private List<DownloadCandidate> customChain;

        public Builder filenameHint(String filenameHint) {
            this.filenameHint = filenameHint;
            return this;
        }

        public Builder referer(String referer) {
            this.referer = referer;
            return this;
        }

        public Builder preference(QualityPreference preference) {
            this.preference = preference;
            return this;
        }

        public Builder customChain(List<DownloadCandidate> chain) {
            this.customChain = chain;
            return this;
        }

        public CvhDownloadRequest build() {
            return new CvhDownloadRequest(filenameHint, referer, preference, customChain);
        }
    }
}
