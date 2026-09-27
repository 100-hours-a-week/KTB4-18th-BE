package com.muse.meomuneum.user.signup.domain;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum SignupTermType {
    // 서비스 이용약관
    SERVICE(1),
    // AI 맞춤 음악 추천 정보 이용 동의
    AIPERSONAL(2),
    // 출생연도·성별의 맞춤 추천 이용 동의
    PROFILE(3),
    // 개인위치정보 수집·이용 동의
    LOCATION(4),
    // 개인정보 처리방침
    PRIVACY(5),
    // 위치기반서비스 이용약관
    LOCATIONTERMS(6);

    private final long initialId;

    SignupTermType(long initialId) {
        this.initialId = initialId;
    }

    public long initialId() {
        return initialId;
    }

    public static Set<String> names() {
        return Stream.of(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
