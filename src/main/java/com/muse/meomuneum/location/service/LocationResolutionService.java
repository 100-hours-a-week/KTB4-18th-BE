package com.muse.meomuneum.location.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.chat.region.domain.RegionLevel;
import com.muse.meomuneum.chat.region.repository.RegionRepository;
import com.muse.meomuneum.location.dto.LocationResolveRequest;
import com.muse.meomuneum.location.dto.LocationResolveResponse;
import com.muse.meomuneum.location.dto.LocationResolveResponse.RegionSummary;
import com.muse.meomuneum.location.dto.LocationResolveResponse.RegionSummaryPair;
import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;
import com.muse.meomuneum.location.provider.RegionCoordinateResolver;
import com.muse.meomuneum.location.provider.ResolvedRegionCode;
import com.muse.meomuneum.location.security.IssuedLocationToken;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;

@Service
public class LocationResolutionService {

    private final RegionCoordinateResolver coordinateResolver;
    private final RegionRepository regionRepository;
    private final LocationResolutionTokenProvider tokenProvider;

    public LocationResolutionService(
            RegionCoordinateResolver coordinateResolver,
            RegionRepository regionRepository,
            LocationResolutionTokenProvider tokenProvider) {
        this.coordinateResolver = coordinateResolver;
        this.regionRepository = regionRepository;
        this.tokenProvider = tokenProvider;
    }

    @Transactional(readOnly = true)
    public LocationResolveResponse resolve(Long userId, LocationResolveRequest request) {
        request.validate();
        ResolvedRegionCode regionCode = coordinateResolver.resolve(request.latitude(), request.longitude());
        Region sido = findRegion(regionCode.sidoCode(), RegionLevel.SIDO);
        Region sigungu = findRegion(regionCode.sigunguCode(), RegionLevel.SIGUNGU);
        validateHierarchy(sido, sigungu);

        IssuedLocationToken token = tokenProvider.issue(userId, sido, sigungu);
        return new LocationResolveResponse(
                null,
                new RegionSummaryPair(RegionSummary.from(sido), RegionSummary.from(sigungu)),
                token.value(),
                token.expiresIn()
        );
    }

    private Region findRegion(String code, RegionLevel level) {
        return regionRepository.findByCodeAndLevelAndActiveTrue(code, level)
                .orElseThrow(() -> new LocationException(LocationErrorCode.REVERSE_GEOCODING_FAILED));
    }

    private void validateHierarchy(Region sido, Region sigungu) {
        if (sigungu.getParent() == null || !sido.getId().equals(sigungu.getParent().getId())) {
            throw new LocationException(LocationErrorCode.REVERSE_GEOCODING_FAILED);
        }
    }
}
