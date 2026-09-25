package com.muse.meomuneum.chat.region.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.chat.region.domain.RegionLevel;

public interface RegionRepository extends JpaRepository<Region, Long> {

    Optional<Region> findByCodeAndActiveTrue(String code);

    List<Region> findAllByLevelAndActiveTrue(RegionLevel level);
}
