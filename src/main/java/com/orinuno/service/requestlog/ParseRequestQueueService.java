package com.orinuno.service.requestlog;

import com.orinuno.repository.ParseRequestRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dedicated transactional facade for claiming queue rows. Lives in its own bean so Spring's
 * {@code @Transactional} proxy intercepts the call from {@link RequestWorker} (avoids the
 * self-invocation pitfall).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseRequestQueueService {

    private final ParseRequestRepository repository;
    private final Clock clock;

    @Transactional
    public Optional<Long> claimNext() {
        Optional<Long> id = repository.selectPendingIdForClaim();
        if (id.isEmpty()) return Optional.empty();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        repository.markClaimed(id.get(), now, now);
        log.info("🔧 Worker claimed parse request id={}", id.get());
        return id;
    }
}
