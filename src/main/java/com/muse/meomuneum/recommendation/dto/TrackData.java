package com.muse.meomuneum.recommendation.dto;

/** 추천 제공자가 반환하는 음악 정보. 실제 AI·음악 API도 이 형태로 변환합니다. */
public record TrackData(String provider, String externalId, String title,
                        String artistName, String coverUrl, String previewUrl) {}
