#ifndef GLADIO_COMPRESSED_TEXTURE_H
#define GLADIO_COMPRESSED_TEXTURE_H

#include "gladio.h"
#include "thread_pool.h"
#include <limits.h>

static inline int getCompressedImageSize(uint32_t format, int width, int height, int level) {
    if (width <= 0 || height <= 0 || level < 0 || level > 31) return 0;
    uint32_t mipWidth = (uint32_t)width;
    uint32_t mipHeight = (uint32_t)height;
    for (int i = 0; i < level; i++) {
        mipWidth = mipWidth > 1 ? mipWidth >> 1 : 1;
        mipHeight = mipHeight > 1 ? mipHeight >> 1 : 1;
    }
    uint64_t blocksX = ((uint64_t)mipWidth + 3u) / 4u;
    uint64_t blocksY = ((uint64_t)mipHeight + 3u) / 4u;
    uint64_t blockSize = format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT ||
                         format == GL_COMPRESSED_RGBA_S3TC_DXT1_EXT ? 8u : 16u;
    uint64_t byteCount = blocksX * blocksY * blockSize;
    return byteCount > (uint64_t)INT_MAX ? 0 : (int)byteCount;
}

static inline bool isSupportedCompressedTextureFormat(uint32_t format) {
    return format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT ||
           format == GL_COMPRESSED_RGBA_S3TC_DXT1_EXT ||
           format == GL_COMPRESSED_RGBA_S3TC_DXT3_EXT ||
           format == GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
}

extern void compressTexImage2D(uint32_t format, int width, int height, void* imageData, void* compressedData);
extern void* decompressTexImage2D(uint32_t format, int width, int height, void* imageData, ThreadPool* threadPool);
extern void freeDecompressedTexImage2D(void* imageData, int width, int height);

#endif
