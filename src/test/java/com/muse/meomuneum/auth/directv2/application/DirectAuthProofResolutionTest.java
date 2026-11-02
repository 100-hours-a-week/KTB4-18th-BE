package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.directv2.controller.DirectAuthRequestParser.Tuple;

class DirectAuthProofResolutionTest {
    @Test
    void multipleCurrentFlowsAndAuthoritativeOtherFlowCannotAuthorizeTargetRebootstrap() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var a = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()), UUID.randomUUID().toString());
            var b = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()), UUID.randomUUID().toString());
            var all = new ArrayList<String>(a.cookies());
            all.addAll(b.cookies());
            var tuple = new Tuple(a.flow().flowId(), 1, UUID.randomUUID().toString());
            assertThatThrownBy(() -> f.rebootstrap.rebootstrap(DirectAuthLoginLogoutTest.request(all), tuple))
                    .hasMessage("AUTH_FLOW_NOT_FOUND");
            Collections.reverse(all);
            assertThatThrownBy(() -> f.rebootstrap.rebootstrap(DirectAuthLoginLogoutTest.request(all), tuple))
                    .hasMessage("AUTH_FLOW_NOT_FOUND");
            var other = new ArrayList<String>(
                    b.cookies().stream().filter(cookie -> cookie.startsWith("MEO_AUTH_FLOW")).toList());
            a.cookies().stream().filter(cookie -> cookie.startsWith("MEO_AUTH_BINDING")).forEach(other::add);
            assertThatThrownBy(() -> f.rebootstrap.rebootstrap(DirectAuthLoginLogoutTest.request(other), tuple))
                    .hasMessage("AUTH_FLOW_NOT_FOUND");
            assertThat(f.flows.find(a.flow().flowId()).orElseThrow().revision()).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM auth_flow_rebootstrap_operations", Integer.class))
                    .isZero();
        }
    }

    @Test
    void validLegacyReadAndExplicitUpgradePreserveIdentityWithoutReadMutation() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            String flowId = UUID.randomUUID().toString();
            String proof = f.cookies.secret();
            f.jdbc.update("""
                    INSERT INTO auth_browser_flows (flow_id, flow_cookie_hash, cookie_expires_at, created_at,
                    updated_at)
                    VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1800 SECOND), CURRENT_TIMESTAMP(6),
                    CURRENT_TIMESTAMP(6))
                    """, flowId, f.cookies.hash("auth-flow-cookie:" + proof));
            var legacy = DirectAuthLoginLogoutTest.request(List.of());
            legacy.setCookies(new Cookie("MEO_AUTH_FLOW", proof));
            var read = f.flowService.current(legacy);
            assertThat(read.flow().flowId()).isEqualTo(flowId);
            assertThat(read.cookies()).isEmpty();
            assertThat(f.flows.find(flowId).orElseThrow().flowKeyHash()).isNull();
            var upgraded = f.flowService.bootstrap(legacy, UUID.randomUUID().toString());
            assertThat(upgraded.flow().flowId()).isEqualTo(flowId);
            assertThat(upgraded.flow().epoch()).isEqualTo(1);
            assertThat(upgraded.flow().flowKeyHash()).isEqualTo(f.cookies.key(flowId));
            assertThat(upgraded.cookies()).hasSize(2);
            assertThat(f.flowService.current(DirectAuthLoginLogoutTest.request(upgraded.cookies())).flow().flowId())
                    .isEqualTo(flowId);
        }
    }
    @Test
    void bindingFallbackRejectsRejectedKeyAmbiguityWhileOneCurrentFlowIgnoresUnrelatedBinding() throws Exception {
        try (var f = new DirectAuthRuntimeFixture(DirectAuthLoginLogoutTest.users())) {
            var a = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()), UUID.randomUUID().toString());
            var b = f.flowService.bootstrap(DirectAuthLoginLogoutTest.request(List.of()), UUID.randomUUID().toString());
            var restoredA = f.rebootstrap.rebootstrap(DirectAuthLoginLogoutTest.request(a.cookies()),
                    new Tuple(a.flow().flowId(), 1, UUID.randomUUID().toString()));
            f.rebootstrap.rebootstrap(DirectAuthLoginLogoutTest.request(b.cookies()),
                    new Tuple(b.flow().flowId(), 1, UUID.randomUUID().toString()));
            var stale = new ArrayList<String>(a.cookies());
            b.cookies().stream().filter(cookie -> cookie.startsWith("MEO_AUTH_FLOW")).forEach(stale::add);
            assertThatThrownBy(() -> f.flowService.resolve(DirectAuthLoginLogoutTest.request(stale), true))
                    .hasMessage("AUTH_FLOW_NOT_FOUND");
            var mismatch = new ArrayList<String>(a.cookies().stream()
                    .filter(cookie -> cookie.startsWith("MEO_AUTH_BINDING")).toList());
            b.cookies().stream().filter(cookie -> cookie.startsWith("MEO_AUTH_FLOW")).forEach(mismatch::add);
            assertThatThrownBy(() -> f.flowService.resolve(DirectAuthLoginLogoutTest.request(mismatch), true))
                    .hasMessage("AUTH_FLOW_NOT_FOUND");
            var current = new ArrayList<String>(restoredA.cookies());
            b.cookies().stream().filter(cookie -> cookie.startsWith("MEO_AUTH_BINDING")).forEach(current::add);
            assertThat(f.flowService.resolve(DirectAuthLoginLogoutTest.request(current), true).flowId())
                    .isEqualTo(a.flow().flowId());
        }
    }

}
