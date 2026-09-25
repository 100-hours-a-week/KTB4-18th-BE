package com.muse.meomuneum.location.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;

public record LocationResolveRequest(Double latitude, Double longitude,
        @JsonProperty("accuracy_meters") Double accuracyMeters) {

    private static final double MAX_ACCURACY_METERS = 100.0;

    public void validate() {
        if (!isFinite(latitude) || latitude < -90.0 || latitude > 90.0 || !isFinite(longitude) || longitude < -180.0
                || longitude > 180.0 || !isFinite(accuracyMeters) || accuracyMeters < 0.0
                || accuracyMeters > MAX_ACCURACY_METERS) {
            throw new LocationException(LocationErrorCode.INVALID_COORDINATES);
        }
    }

    private boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }
}
