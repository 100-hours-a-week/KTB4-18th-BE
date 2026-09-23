package com.muse.meomuneum.location.provider;

public interface RegionCoordinateResolver {

    ResolvedRegionCode resolve(double latitude, double longitude);
}
