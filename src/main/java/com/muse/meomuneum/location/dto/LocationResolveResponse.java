package com.muse.meomuneum.location.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.muse.meomuneum.chat.region.domain.Region;

public record LocationResolveResponse(
        @JsonProperty("map_dot") MapDotSummary mapDot,
        RegionSummaryPair region,
        @JsonProperty("location_resolution_token") String locationResolutionToken,
        @JsonProperty("expires_in") long expiresIn) {

    public record MapDotSummary(
            @JsonProperty("map_dot_id") Long mapDotId,
            String code) {
    }

    public record RegionSummaryPair(RegionSummary sido, RegionSummary sigungu) {
    }

    public record RegionSummary(
            @JsonProperty("region_id") Long regionId,
            String code,
            String name) {

        public static RegionSummary from(Region region) {
            return new RegionSummary(region.getId(), region.getCode(), region.getName());
        }
    }
}
