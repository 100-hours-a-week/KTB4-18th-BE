package com.muse.meomuneum.location.dto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;

class LocationResolveRequestTest {

    @Test
    void acceptsCoordinatesWithAccuracyAtBoundary() {
        LocationResolveRequest request = new LocationResolveRequest(37.3595704, 127.105399, 100.0);

        assertThatCode(request::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsAccuracyOverOneHundredMeters() {
        LocationResolveRequest request = new LocationResolveRequest(37.3595704, 127.105399, 100.1);

        assertInvalidCoordinates(request);
    }

    @Test
    void rejectsMissingOrOutOfRangeCoordinate() {
        assertInvalidCoordinates(new LocationResolveRequest(null, 127.105399, 10.0));
        assertInvalidCoordinates(new LocationResolveRequest(91.0, 127.105399, 10.0));
        assertInvalidCoordinates(new LocationResolveRequest(37.3595704, -181.0, 10.0));
        assertInvalidCoordinates(new LocationResolveRequest(37.3595704, 127.105399, null));
    }

    private void assertInvalidCoordinates(LocationResolveRequest request) {
        assertThatThrownBy(request::validate)
                .isInstanceOf(LocationException.class)
                .extracting(exception -> ((LocationException) exception).getErrorCode())
                .isEqualTo(LocationErrorCode.INVALID_COORDINATES);
    }
}
