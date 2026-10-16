#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdexcept>

static void * system_loader() {
    static void * loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    return loader;
}

// Initialize Vulkan-Hpp's dispatcher lazily; the pinned backend's direct core calls have shims below.
extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char * name) {
    auto loader = system_loader();
    static auto get_proc = loader ? reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr")) : nullptr;
    return get_proc ? get_proc(instance, name) : nullptr;
}

// The pinned backend also calls this core 1.1 entry point directly. Resolve it
// lazily as well, rather than introducing a hard libvulkan dependency on API 23.
extern "C" VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(
    VkPhysicalDevice device, VkPhysicalDeviceFeatures2 * features) {
    static auto get_features = system_loader() ? reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        dlsym(system_loader(), "vkGetPhysicalDeviceFeatures2")) : nullptr;
    if (!get_features) throw std::runtime_error("Vulkan 1.2 unavailable");
    get_features(device, features);
}

extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char * name) {
    static auto get_proc = system_loader() ? reinterpret_cast<PFN_vkGetDeviceProcAddr>(
        dlsym(system_loader(), "vkGetDeviceProcAddr")) : nullptr;
    return get_proc ? get_proc(device, name) : nullptr;
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkCmdCopyBuffer(VkCommandBuffer command, VkBuffer source,
    VkBuffer destination, uint32_t count, const VkBufferCopy * regions) {
    static auto copy = system_loader() ? reinterpret_cast<PFN_vkCmdCopyBuffer>(
        dlsym(system_loader(), "vkCmdCopyBuffer")) : nullptr;
    if (!copy) throw std::runtime_error("Vulkan buffer copy unavailable");
    copy(command, source, destination, count, regions);
}
