package com.muse.meomuneum.musicrecord.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Request;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Response;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;

@Service
public class SongRegistrationService {
    private static final int MAX_IN_FLIGHT = 64;
    private final MusicRecordRepository repository;
    private final ItunesMusicSearchClient itunes;
    private final MusicSearchCandidateCache candidates;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final Map<String, CompletableFuture<Response>> inFlight = new HashMap<>();

    public SongRegistrationService(MusicRecordRepository repository, ItunesMusicSearchClient itunes,
            MusicSearchCandidateCache candidates, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.itunes = itunes;
        this.candidates = candidates;
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads = new TransactionTemplate(transactions);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setReadOnly(true);
    }

    public Response register(Request request) {
        String id = request.externalTrackId();
        var stored = repository.findMusic("ITUNES", id);
        if (stored.isPresent()) {
            return new Response(stored.orElseThrow(), false);
        }
        CompletableFuture<Response> future;
        boolean owner;
        synchronized (inFlight) {
            future = inFlight.get(id);
            owner = future == null;
            if (owner) {
                if (inFlight.size() >= MAX_IN_FLIGHT) {
                    throw new MusicRecordException("song_registration_busy", HttpStatus.SERVICE_UNAVAILABLE,
                            "song registration busy");
                }
                future = new CompletableFuture<>();
                inFlight.put(id, future);
            }
        }
        if (!owner) {
            try {
                return new Response(future.join().music(), false);
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw exception;
            }
        }
        try {
            Response response = insertOrReuse(id);
            future.complete(response);
            return response;
        } catch (RuntimeException | Error exception) {
            future.completeExceptionally(exception);
            throw exception;
        } finally {
            synchronized (inFlight) {
                inFlight.remove(id, future);
            }
        }
    }

    private Response insertOrReuse(String id) {
        var stored = repository.findMusic("ITUNES", id);
        if (stored.isPresent()) {
            return new Response(stored.orElseThrow(), false);
        }
        var selected = candidates.findCandidate(itunes.country(), id).orElseGet(() -> itunes.lookup(id)
                .orElseThrow(() -> new MusicRecordException("itunes_track_not_found", HttpStatus.NOT_FOUND,
                        "music not found")));
        try {
            return writes.execute(status -> new Response(repository.insertSelectedMusic(selected), true));
        } catch (DuplicateKeyException exception) {
            return reads.execute(status -> repository.findMusic("ITUNES", id)
                    .map(music -> new Response(music, false)).orElseThrow(() -> exception));
        }
    }
}
