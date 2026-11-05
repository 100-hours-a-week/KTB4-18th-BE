package com.muse.meomuneum.auth.directv2.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.auth.directv2.application.DirectAuthLoginLogoutTest;
import com.muse.meomuneum.auth.directv2.application.DirectAuthRuntimeFixture;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthOperationController;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;
import com.muse.meomuneum.auth.directv2.controller.DirectAuthResponseWriter;
import com.muse.meomuneum.auth.dto.LoginRequest;

class DirectAuthLoginStatusHttpTest {
    @Test
    void rebootstrapStatusAndCurrentSummaryProjectSameCanonicalRevisionWithoutCredentials() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var bootstrap = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()),
                    UUID.randomUUID().toString());
            var request = DirectAuthLoginLogoutTest.request(bootstrap.cookies());
            var tuple = new Tuple(bootstrap.flow().flowId(), 1, UUID.randomUUID().toString());
            f.login.login(request, new LoginRequest("test@example.invalid", "test-only-password"), tuple,
                    System.currentTimeMillis() + 600_000);
            var restored = f.rebootstrap.rebootstrap(request,
                    new Tuple(tuple.flowId(), 1, UUID.randomUUID().toString()));
            var controller = new DirectAuthOperationController(new DirectAuthRequestParser(),
                    new DirectAuthResponseWriter(),
                    f.flowService, f.login, f.logout, f.recovery, f.refresh, f.rebootstrap);
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            var response = mvc.perform(get("/api/v1/auth/login/operations/" + tuple.operationId())
                    .header("X-Auth-Login-Protocol", "direct-v2").header("X-Auth-Flow-Id", tuple.flowId())
                    .header("X-Auth-Flow-Epoch", "1")
                    .cookie(DirectAuthLoginLogoutTest.request(restored.cookies()).getCookies())).andReturn()
                    .getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString())
                    .contains("\"resultRevision\":\"" + restored.flow().revision() + "\"")
                    .doesNotContain("access_token");
            assertThat(response.getHeaders("Set-Cookie")).isEmpty();
            assertThat(response.getHeader("X-Auth-Flow-Revision")).isEqualTo(Long.toString(restored.flow().revision()));
        }
    }
}
