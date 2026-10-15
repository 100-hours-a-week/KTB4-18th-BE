package com.muse.meomuneum.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueStatusResponse(HazelcastAuthStateStore.TicketStatus status,
        @JsonInclude(JsonInclude.Include.ALWAYS) HazelcastAuthStateStore.LoginOutcome loginOutcome,
        Integer usersAhead, int nextPollSeconds,
        String diagnosticCorrId) {
}
