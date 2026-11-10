package dev.proofpay.release;

import dev.proofpay.domain.Release;
import dev.proofpay.domain.Repositories.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Backup for webhooks: nudges every unfinished release. Safe because each step is idempotent. */
@Component
public class ReleasePoller {

    private static final Logger log = LoggerFactory.getLogger(ReleasePoller.class);

    private final ReleaseRepository releases;
    private final ReleaseService service;

    public ReleasePoller(ReleaseRepository releases, ReleaseService service) {
        this.releases = releases;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${proofpay.poll-interval-ms}", initialDelay = 10_000)
    public void poll() {
        for (Release r : releases.findByStatusIn(ReleaseService.IN_FLIGHT)) {
            try {
                service.advance(r.getId());
            } catch (Exception e) {
                log.warn("Poll of release {} failed: {}", r.getId(), e.getMessage());
            }
        }
    }
}
