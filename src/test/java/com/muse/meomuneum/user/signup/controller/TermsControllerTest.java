package com.muse.meomuneum.user.signup.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.user.signup.repository.TermsRepository;
import com.muse.meomuneum.user.signup.repository.TermsRepository.TermRow;

class TermsControllerTest {
    private final TermsRepository repository = mock(TermsRepository.class);
    private final TermsController controller = new TermsController(repository);
    private final TermRow aiTerm = new TermRow(7L, "AIPERSONAL", "v0.3", "AI 약관", true,
            "2026-09-25T10:00:00Z", "가입하려면 동의해야 합니다.");

    @Test
    void returnsCurrentTermIdWithoutAssumingInitialId() {
        when(repository.findCurrent(any(), eq(null))).thenReturn(List.of(aiTerm));

        var response = controller.list(null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("terms retrieved", response.getBody().message());
        assertEquals(7L, response.getBody().data().items().getFirst().termsId());
    }

    @Test
    void invalidQueryRetainsPublishedMessage() {
        var response = controller.list("UNKNOWN", null);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("invalid query parameter", response.getBody().message());
    }

    @Test
    void returnsExactHistoricalDetailAndNotFound() {
        when(repository.findById(7L)).thenReturn(Optional.of(aiTerm));
        var found = controller.detail(7L);
        assertEquals("term retrieved", found.getBody().message());
        assertEquals("가입하려면 동의해야 합니다.", found.getBody().data().content());

        var missing = controller.detail(8L);
        assertEquals(404, missing.getStatusCode().value());
        assertTrue(missing.getBody().data() == null);
        assertEquals("term not found", missing.getBody().message());
    }
}
