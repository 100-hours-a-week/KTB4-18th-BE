package com.muse.meomuneum.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.chat.region.domain.RegionLevel;
import com.muse.meomuneum.chat.region.repository.RegionRepository;
import com.muse.meomuneum.location.dto.LocationResolveRequest;
import com.muse.meomuneum.location.dto.LocationResolveResponse;
import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;
import com.muse.meomuneum.location.provider.RegionCoordinateResolver;
import com.muse.meomuneum.location.provider.ResolvedRegionCode;
import com.muse.meomuneum.location.security.IssuedLocationToken;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;

class LocationResolutionServiceTest {

    private RegionCoordinateResolver coordinateResolver;
    private RegionRepository regionRepository;
    private LocationResolutionTokenProvider tokenProvider;
    private LocationResolutionService service;
    private Region sido;
    private Region sigungu;

    @BeforeEach
    void setUp() {
        coordinateResolver = mock(RegionCoordinateResolver.class);
        regionRepository = mock(RegionRepository.class);
        tokenProvider = mock(LocationResolutionTokenProvider.class);
        service = new LocationResolutionService(coordinateResolver, regionRepository, tokenProvider);

        sido = Region.create("41", "경기도", RegionLevel.SIDO, null);
        ReflectionTestUtils.setField(sido, "id", 9L);
        sigungu = Region.create("41135", "성남시 분당구", RegionLevel.SIGUNGU, sido);
        ReflectionTestUtils.setField(sigungu, "id", 25L);
    }

    @Test
    void resolvesActiveRegionsAndIssuesTokenWithoutCoordinatesInResponse() {
        LocationResolveRequest request = new LocationResolveRequest(37.3595704, 127.105399, 18.5);
        when(coordinateResolver.resolve(request.latitude(), request.longitude()))
                .thenReturn(new ResolvedRegionCode("41", "41135"));
        when(regionRepository.findByCodeAndLevelAndActiveTrue("41", RegionLevel.SIDO))
                .thenReturn(Optional.of(sido));
        when(regionRepository.findByCodeAndLevelAndActiveTrue("41135", RegionLevel.SIGUNGU))
                .thenReturn(Optional.of(sigungu));
        when(tokenProvider.issue(7L, sido, sigungu)).thenReturn(new IssuedLocationToken("loc_token", 300));

        LocationResolveResponse response = service.resolve(7L, request);

        assertThat(response.mapDot()).isNull();
        assertThat(response.region().sido().regionId()).isEqualTo(9L);
        assertThat(response.region().sigungu().regionId()).isEqualTo(25L);
        assertThat(response.locationResolutionToken()).isEqualTo("loc_token");
        assertThat(response.expiresIn()).isEqualTo(300L);
    }

    @Test
    void rejectsLowAccuracyBeforeCallingExternalResolver() {
        LocationResolveRequest request = new LocationResolveRequest(37.3595704, 127.105399, 100.1);

        assertThatThrownBy(() -> service.resolve(7L, request))
                .isInstanceOf(LocationException.class)
                .extracting(exception -> ((LocationException) exception).getErrorCode())
                .isEqualTo(LocationErrorCode.INVALID_COORDINATES);
        verifyNoInteractions(coordinateResolver, regionRepository, tokenProvider);
    }

    @Test
    void rejectsRegionNotPresentInActiveSnapshot() {
        LocationResolveRequest request = new LocationResolveRequest(37.3595704, 127.105399, 18.5);
        when(coordinateResolver.resolve(request.latitude(), request.longitude()))
                .thenReturn(new ResolvedRegionCode("41", "41135"));
        when(regionRepository.findByCodeAndLevelAndActiveTrue("41", RegionLevel.SIDO))
                .thenReturn(Optional.of(sido));
        when(regionRepository.findByCodeAndLevelAndActiveTrue("41135", RegionLevel.SIGUNGU))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(7L, request))
                .isInstanceOf(LocationException.class)
                .extracting(exception -> ((LocationException) exception).getErrorCode())
                .isEqualTo(LocationErrorCode.REVERSE_GEOCODING_FAILED);
    }

    @Test
    void rejectsSigunguThatDoesNotBelongToResolvedSido() {
        Region otherSido = Region.create("11", "서울특별시", RegionLevel.SIDO, null);
        ReflectionTestUtils.setField(otherSido, "id", 1L);
        Region mismatchedSigungu = Region.create("11110", "종로구", RegionLevel.SIGUNGU, otherSido);
        ReflectionTestUtils.setField(mismatchedSigungu, "id", 2L);
        LocationResolveRequest request = new LocationResolveRequest(37.3595704, 127.105399, 18.5);
        when(coordinateResolver.resolve(request.latitude(), request.longitude()))
                .thenReturn(new ResolvedRegionCode("41", "11110"));
        when(regionRepository.findByCodeAndLevelAndActiveTrue("41", RegionLevel.SIDO))
                .thenReturn(Optional.of(sido));
        when(regionRepository.findByCodeAndLevelAndActiveTrue("11110", RegionLevel.SIGUNGU))
                .thenReturn(Optional.of(mismatchedSigungu));

        assertThatThrownBy(() -> service.resolve(7L, request))
                .isInstanceOf(LocationException.class)
                .extracting(exception -> ((LocationException) exception).getErrorCode())
                .isEqualTo(LocationErrorCode.REVERSE_GEOCODING_FAILED);
    }
}
