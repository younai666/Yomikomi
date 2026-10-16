#pragma once

#include <cstdlib>

// aligned_alloc is API 28+, but the app's CPU path still supports API 23.
#if defined(__ANDROID__) && __ANDROID_API__ < 28
namespace std {
inline void * yomikomi_aligned_alloc(size_t alignment, size_t size) {
    void * result = nullptr;
    return posix_memalign(&result, alignment, size) == 0 ? result : nullptr;
}
}
#define aligned_alloc yomikomi_aligned_alloc
#endif
