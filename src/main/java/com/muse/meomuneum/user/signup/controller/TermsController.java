package com.muse.meomuneum.user.signup.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.user.signup.domain.SignupTermType;
import com.muse.meomuneum.user.signup.repository.TermsRepository;
import com.muse.meomuneum.user.signup.repository.TermsRepository.TermRow;

@RestController
@RequestMapping("/api/v1/terms")
public class TermsController {
    private final TermsRepository repository;
    private final Clock clock;

    public TermsController(TermsRepository repository) {
        this.repository = repository;
        this.clock = Clock.systemUTC();
    }

    @GetMapping
    public ResponseEntity<ApiResponse<TermsList>> list(@RequestParam(required = false) String type,
            @RequestParam(name = "effective_at", required = false) String effectiveAt) {
        if (type != null && !SignupTermType.names().contains(type)) {
            return ResponseEntity.badRequest().body(ApiResponse.of("invalid query parameter", null));
        }
        Instant at;
        try {
            at = effectiveAt == null ? clock.instant() : Instant.parse(effectiveAt);
        } catch (DateTimeParseException exception) {
            return ResponseEntity.badRequest().body(ApiResponse.of("invalid query parameter", null));
        }
        List<TermSummary> items = repository.findCurrent(at, type).stream().map(TermSummary::from).toList();
        return ResponseEntity.ok(ApiResponse.of("terms retrieved", new TermsList(items)));
    }

    @GetMapping("/{termsId}")
    public ResponseEntity<ApiResponse<TermDetail>> detail(@PathVariable long termsId) {
        return repository.findById(termsId)
                .map(row -> ResponseEntity.ok(ApiResponse.of("term retrieved", TermDetail.from(row))))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.of("term not found", null)));
    }

    public record TermsList(List<TermSummary> items) {}

    public record TermSummary(@JsonProperty("terms_id") long termsId, String type, String version,
            String title, @JsonProperty("is_required") boolean required,
            @JsonProperty("effective_at") String effectiveAt) {
        static TermSummary from(TermRow row) {
            return new TermSummary(row.id(), row.type(), row.version(), row.title(), row.required(),
                    row.effectiveAt());
        }
    }

    public record TermDetail(@JsonProperty("terms_id") long termsId, String type, String version,
            String title, String content, @JsonProperty("is_required") boolean required,
            @JsonProperty("effective_at") String effectiveAt) {
        static TermDetail from(TermRow row) {
            return new TermDetail(row.id(), row.type(), row.version(), row.title(), row.content(), row.required(),
                    row.effectiveAt());
        }
    }
}
