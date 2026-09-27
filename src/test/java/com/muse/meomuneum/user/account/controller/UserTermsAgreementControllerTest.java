package com.muse.meomuneum.user.account.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.user.account.service.UserAccountService;
import com.muse.meomuneum.user.account.service.UserTermsAgreementService;

class UserTermsAgreementControllerTest {

    private final UserAccountService userAccountService = org.mockito.Mockito.mock(UserAccountService.class);
    private final UserTermsAgreementService termsAgreementService = org.mockito.Mockito
            .mock(UserTermsAgreementService.class);
    private final CurrentUserResolver currentUserResolver = org.mockito.Mockito.mock(CurrentUserResolver.class);
    private final Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(7L, null,
            List.of());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(currentUserResolver.resolve(any())).thenReturn(7L);
        mvc = MockMvcBuilders.standaloneSetup(
                new UserAccountController(userAccountService, currentUserResolver, termsAgreementService)).build();
    }

    @Test
    void returnsOwnAgreementHistoryWithSpecifiedFields() throws Exception {
        when(termsAgreementService.getAgreements(7L)).thenReturn(List.of(
                new UserTermsAgreementService.AgreementItem(20, 12, "2026-09-27T00:00:00Z", null)));

        mvc.perform(get("/api/v1/users/me/terms-agreements").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("agreements retrieved"))
                .andExpect(jsonPath("$.data.items[0].agreement_id").value(20))
                .andExpect(jsonPath("$.data.items[0].terms_id").value(12))
                .andExpect(jsonPath("$.data.items[0].agreed_at").value("2026-09-27T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].withdrawn_at").doesNotExist());
        verify(termsAgreementService).getAgreements(7L);
    }

    @Test
    void createsAgreementWithSpecifiedBodyAndResponse() throws Exception {
        when(termsAgreementService.agree(7L, List.of(12L, 13L))).thenReturn(List.of(20L, 21L));

        mvc.perform(post("/api/v1/users/me/terms-agreements")
                .principal(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terms_ids\":[12,13]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("terms agreed"))
                .andExpect(jsonPath("$.data.agreement_ids[0]").value(20))
                .andExpect(jsonPath("$.data.agreement_ids[1]").value(21));
        verify(termsAgreementService).agree(7L, List.of(12L, 13L));
    }

    @Test
    void rejectsMissingOrNullTermsIdsWithoutCallingService() throws Exception {
        for (String body : List.of("{}", "{\"terms_ids\":null}")) {
            mvc.perform(post("/api/v1/users/me/terms-agreements")
                    .principal(authentication)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(termsAgreementService, never()).agree(any(), any());
    }

    @Test
    void withdrawsAgreementWithBodylessNoContent() throws Exception {
        mvc.perform(delete("/api/v1/users/me/terms-agreements/12").principal(authentication))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(termsAgreementService).withdraw(7L, 12L);
    }
}
