package com.dailylanguage.modelcalljob.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ModelCallJobMapper {

    StoredModelCallJob insertAndReturn(NewModelCallJobRow job);

    Optional<StoredModelCallJob> findByIdAndUserId(
            @Param("jobId") UUID jobId,
            @Param("userId") UUID userId);
}
