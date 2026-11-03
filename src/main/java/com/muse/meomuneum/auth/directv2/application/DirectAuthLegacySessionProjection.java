package com.muse.meomuneum.auth.directv2.application;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository.Session;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;

@Component
public class DirectAuthLegacySessionProjection {
    private final HazelcastAuthStateStore store;
    private final DirectAuthCookieService cookies;

    public DirectAuthLegacySessionProjection(HazelcastAuthStateStore store, DirectAuthCookieService cookies) {
        this.store = store;
        this.cookies = cookies;
    }

    public void project(DirectAuthFlowState flow, Session session, LocalDateTime now) {
        project(flow, session, now, session.active(flow, now));
    }

    public void project(DirectAuthFlowState flow, Session session, LocalDateTime now, boolean permitted) {
        try (var ignored = store.acquireRequestPermit()) {
            var existing = store.getSession(session.sid());
            if (existing != null && (!"direct-v2".equals(existing.ownerClaimId())
                    || !java.util.Objects.equals(existing.userId(), session.userId()))) {
                throw DirectAuthFailure.unavailable();
            }
            if (!permitted || !session.active(flow, now)) {
                if (existing != null) {
                    store.revokeSession(session.sid(), session.userId());
                }
                return;
            }
            if (session.accessJti() == null || session.accessExpiry() == null || session.refreshHash() == null) {
                throw DirectAuthFailure.unavailable();
            }
            if (existing != null) {
                if (existing.status() != HazelcastAuthStateStore.SessionStatus.ACTIVE
                        || existing.generation() > session.refreshGeneration()) {
                    throw DirectAuthFailure.unavailable();
                }
                if (existing.generation() == session.refreshGeneration()
                        && java.util.Objects.equals(existing.currentJtiHash(),
                                cookies.hash("jti:" + session.accessJti()))
                        && java.util.Objects.equals(existing.currentRefreshHash(), session.refreshHash())
                        && !existing.refreshIdleExpiresAt().isAfter(session.idleExpiry().toInstant(ZoneOffset.UTC))
                        && !existing.refreshAbsoluteExpiresAt()
                                .isAfter(session.absoluteExpiry().toInstant(ZoneOffset.UTC))) {
                    return;
                }
                var updated = new HazelcastAuthStateStore.AuthSession(session.userId(), session.sid(), "direct-v2",
                        HazelcastAuthStateStore.SessionStatus.ACTIVE, cookies.hash("jti:" + session.accessJti()),
                        session.refreshHash(), null, null, null, null, null, null, null, 0, null, null,
                        session.refreshGeneration(), existing.createdAt(), now.toInstant(ZoneOffset.UTC),
                        session.accessExpiry().toInstant(ZoneOffset.UTC),
                        session.idleExpiry().toInstant(ZoneOffset.UTC),
                        session.absoluteExpiry().toInstant(ZoneOffset.UTC), null, null, "direct-v2", null);
                if (!store.replaceSession(existing, updated)) {
                    throw DirectAuthFailure.unavailable();
                }
                return;
            }
            store.createSession(session.userId(), session.sid(), session.accessJti(), session.refreshHash(), null,
                    session.encryptedRefresh(), session.accessExpiry().toInstant(ZoneOffset.UTC),
                    session.idleExpiry().toInstant(ZoneOffset.UTC), "direct-v2", "direct-v2");
            if (session.refreshGeneration() != 1) {
                throw DirectAuthFailure.unavailable();
            }
        }
    }
}
