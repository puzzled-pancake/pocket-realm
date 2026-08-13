#ifndef VORTEK_TIMELINE_SEMAPHORE_H
#define VORTEK_TIMELINE_SEMAPHORE_H

#include "vortek.h"

extern bool TimelineSemaphore_asyncWait(
    VkContext* context,
    VtRequestDecode* decode,
    uint64_t deviceToken,
    VkDevice device,
    uint64_t timeout);

#endif
