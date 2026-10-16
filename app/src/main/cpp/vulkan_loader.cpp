#include <vulkan/vulkan.h>
#include <dlfcn.h>

// ggml-vulkan uses Vulkan-Hpp's dynamic dispatcher; this is its only loader entry point.
extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char * name) {
    static void * loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    static auto get_proc = loader ? reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr")) : nullptr;
    return get_proc ? get_proc(instance, name) : nullptr;
}
