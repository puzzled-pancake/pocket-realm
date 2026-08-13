#ifndef VORTEK_ASYNC_PIPELINE_CREATOR_H
#define VORTEK_ASYNC_PIPELINE_CREATOR_H

#include "vortek.h"

typedef enum PipelineType {
    PIPELINE_TYPE_GRAPHICS,
    PIPELINE_TYPE_COMPUTE
} PipelineType;

extern bool AsyncPipelineCreator_create(
    VkContext* context,
    PipelineType type,
    VtRequestDecode* decode,
    uint64_t deviceToken,
    uint64_t pipelineCacheToken,
    uint32_t pipelineCount,
    VkDevice device,
    VkPipelineCache pipelineCache);

#endif
