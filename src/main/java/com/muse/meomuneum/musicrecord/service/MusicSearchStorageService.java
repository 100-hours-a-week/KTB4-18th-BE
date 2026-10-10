package com.muse.meomuneum.musicrecord.service;

import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.music.domain.MusicMetadataPolicy;
import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;

@Service
public class MusicSearchStorageService {
    private static final Logger log = LoggerFactory.getLogger(MusicSearchStorageService.class);
    private final MusicRecordRepository repository;
    private final RetryTemplate retry;
    private final TransactionTemplate transaction;

    public MusicSearchStorageService(MusicRecordRepository repository,
            @Qualifier("musicSearchStorageRetry") RetryTemplate retry, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.retry = retry;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async("musicSearchStorageExecutor")
    public void store(List<MusicItem> snapshot) {
        for (MusicItem item : snapshot) {
            if (Thread.currentThread().isInterrupted()) {
                interrupted(item, 0);
                return;
            }
            var attempts = new AtomicInteger();
            try {
                validate(item);
                retry.execute(() -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt > 1) {
                        log.info("event=music_search_storage_retry provider={} externalId={} attempt={}",
                                item.provider(), item.external_music_id(), attempt);
                    }
                    transaction.executeWithoutResult(status -> repository.insertMusicIfAbsent(item));
                    return null;
                });
                log.info("event=music_search_storage_success provider={} externalId={} attempts={}",
                        item.provider(), item.external_music_id(), attempts.get());
            } catch (RetryException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted(item, attempts.get());
                    return;
                }
                Throwable failure = exception.getLastException();
                if (failure instanceof Error error) {
                    throw error;
                }
                log.warn("event=music_search_storage_failed provider={} externalId={} attempts={} type={} exception={}",
                        item.provider(), item.external_music_id(), attempts.get(),
                        isRetryable(failure) ? "exhausted" : "nonretryable", failure.getClass().getSimpleName());
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted(item, attempts.get());
                    return;
                }
                log.warn("event=music_search_storage_failed provider={} externalId={} attempts={} type={} exception={}",
                        item.provider(), item.external_music_id(), attempts.get(),
                        attempts.get() == 0 ? "invalid" : "nonretryable", exception.getClass().getSimpleName());
            }
        }
    }

    private void validate(MusicItem item) {
        if (!"ITUNES".equals(item.provider()) || item.external_music_id() == null
                || item.external_music_id().length() > 64 || !item.external_music_id().matches("[0-9]+")) {
            throw new MusicMetadataException("music_identity_invalid");
        }
        MusicMetadataPolicy.validate(item.title(), item.artist_name());
    }

    private void interrupted(MusicItem item, int attempts) {
        Thread.currentThread().interrupt();
        log.warn("event=music_search_storage_interrupted provider={} externalId={} attempts={}",
                item.provider(), item.external_music_id(), attempts);
    }

    public static boolean isRetryable(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean transientFailure = false;
        boolean connectionFailure = false;
        boolean transientSql = false;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof MusicMetadataException || cause instanceof DataIntegrityViolationException
                    || cause instanceof BadSqlGrammarException || cause instanceof InvalidDataAccessApiUsageException
                    || cause instanceof PermissionDeniedDataAccessException
                    || cause instanceof SQLInvalidAuthorizationSpecException || cause instanceof Error) {
                return false;
            }
            transientFailure |= cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException;
            connectionFailure |= cause instanceof CannotCreateTransactionException
                    || cause instanceof DataAccessResourceFailureException;
            transientSql |= cause instanceof SQLTransientException || cause instanceof SQLRecoverableException;
        }
        return transientFailure || (connectionFailure && transientSql);
    }
}
