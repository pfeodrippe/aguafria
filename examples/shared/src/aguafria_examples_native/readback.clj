(ns aguafria-examples-native.readback
  "One bounded Vulkan frame readback, owned and completed by the render thread."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.fmt :as fmt]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as vk]
            [aguafria-examples-native.bindings.runtime :as stdio]))

(az/defextern fwrite
  {:zig/prefix "pub extern"}
  :- :usize
  [[data [:*const :anyopaque]] [size :usize] [count :usize]
   [file [:optional [:* stdio/AguafriaFile]]]])

;; 0 idle, 1 producer copying, 2 queued, 3 rendering, 4 saved,
;; 5 unsupported/resource failure, 6 file failure. Terminal states require ack.
(az/defvar state :u8 0)

(az/defvar filename [:array 1024 :u8] (mem/zeroes (az/type [:array 1024 :u8])))

(az/defvar buffer vk/VkBuffer null)

(az/defvar memory vk/VkDeviceMemory null)

(az/defvar mapped [:optional [:* :anyopaque]] null)

(az/defvar width :u32 0)

(az/defvar height :u32 0)

(az/defn status :- :u8 []
  (ak/atomicLoad :u8 (ak/& state) :.acquire))

(az/defn acknowledge! :- :bool []
  (let [current (status)]
    (and (>= current 4)
         (ak/== (ak/cmpxchgStrong :u8 (ak/& state) current 0 :.acq_rel :.acquire) null))))

(az/defn request!
  "Copy a NUL-terminated path; reject empty/oversized paths and an occupied slot."
  :- :bool
  [[path [:pointer {:size :c :const? true} :u8]]]
  (when (ak/== path null) (ak/return false))
  (let [^{:var :usize} length 0]
    (while (and (< length 1024) (ak/!= (az/index path length) 0))
      (set! length (+ length 1)))
    (when (or (ak/== length 0) (>= length 1024)) (ak/return false))
    (when (ak/!= (ak/cmpxchgStrong :u8 (ak/& state) 0 1 :.acq_rel :.acquire) null)
      (ak/return false))
    (dotimes [index (+ length 1)]
      (set! (az/index filename index) (az/index path index)))
    (ak/atomicStore :u8 (ak/& state) 2 :.release)
    true))

(az/defn supported-format? :- :bool [[format vk/VkFormat]]
  (or (ak/== format vk/VK_FORMAT_B8G8R8A8_UNORM)
      (ak/== format vk/VK_FORMAT_B8G8R8A8_SRGB)
      (ak/== format vk/VK_FORMAT_R8G8B8A8_UNORM)
      (ak/== format vk/VK_FORMAT_R8G8B8A8_SRGB)))

(az/defn reject! :- :void []
  (ak/atomicStore :u8 (ak/& state) 5 :.release))

(az/defn release! :- :void [[device vk/VkDevice]]
  (when (ak/!= mapped null) (vk/vkUnmapMemory device memory))
  (when (ak/!= buffer null) (vk/vkDestroyBuffer device buffer null))
  (when (ak/!= memory null) (vk/vkFreeMemory device memory null))
  (az/set-many! mapped null buffer null memory null))

(az/defn prepare!
  "Render-thread only. Allocate at most 64 MiB; require coherent host memory."
  :- :bool
  [[device vk/VkDevice] [physical vk/VkPhysicalDevice] [extent vk/VkExtent2D]]
  (az/set-many! width (az/field extent width) height (az/field extent height))
  (let [bytes (* (ak/as :u64 width) height 4)
        info (vk/VkBufferCreateInfo
               {:sType vk/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
                :size bytes :usage vk/VK_BUFFER_USAGE_TRANSFER_DST_BIT
                :sharingMode vk/VK_SHARING_MODE_EXCLUSIVE})
        ^:var requirements (mem/zeroes (az/type vk/VkMemoryRequirements))
        ^:var properties (mem/zeroes (az/type vk/VkPhysicalDeviceMemoryProperties))
        ^{:var :u32} selected 0xffffffff]
    (when (or (ak/== bytes 0) (> bytes (* 64 1024 1024))) (ak/return false))
    (when (ak/!= (vk/vkCreateBuffer device (ak/& info) null (ak/& buffer)) vk/VK_SUCCESS)
      (ak/return false))
    (vk/vkGetBufferMemoryRequirements device buffer (ak/& requirements))
    (vk/vkGetPhysicalDeviceMemoryProperties physical (ak/& properties))
    (dotimes [index (az/field properties memoryTypeCount)]
      (let [required (ak/| vk/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT vk/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
            flags (az/field (az/index (az/field properties memoryTypes) index) propertyFlags)]
        (when (and (ak/== selected 0xffffffff)
                   (ak/!= (ak/& (az/field requirements memoryTypeBits)
                                (ak/<< (ak/as :u32 1) (ak/as :u5 (ak/intCast index)))) 0)
                   (ak/== (ak/& flags required) required))
          (set! selected (ak/intCast index)))))
    (when (ak/== selected 0xffffffff) (release! device) (ak/return false))
    (let [allocation (vk/VkMemoryAllocateInfo
                       {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
                        :allocationSize (az/field requirements size) :memoryTypeIndex selected})]
      (when (or (ak/!= (vk/vkAllocateMemory device (ak/& allocation) null (ak/& memory)) vk/VK_SUCCESS)
                (ak/!= (vk/vkBindBufferMemory device buffer memory 0) vk/VK_SUCCESS)
                (ak/!= (vk/vkMapMemory device memory 0 bytes 0 (ak/& mapped)) vk/VK_SUCCESS))
        (release! device)
        (ak/return false)))
    (ak/atomicStore :u8 (ak/& state) 3 :.release)
    true))

(az/defn record!
  "After the color pass: copy to staging, make host reads visible, restore presentation."
  :- :void
  [[command vk/VkCommandBuffer] [source vk/VkImage]]
  (let [^:var barrier (vk/VkImageMemoryBarrier
                       {:sType vk/VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                        :srcAccessMask vk/VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                        :dstAccessMask vk/VK_ACCESS_TRANSFER_READ_BIT
                        :oldLayout vk/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
                        :newLayout vk/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                        :srcQueueFamilyIndex vk/VK_QUEUE_FAMILY_IGNORED
                        :dstQueueFamilyIndex vk/VK_QUEUE_FAMILY_IGNORED
                        :image source
                        :subresourceRange (vk/VkImageSubresourceRange
                                            {:aspectMask vk/VK_IMAGE_ASPECT_COLOR_BIT
                                             :levelCount 1 :layerCount 1})})
        region (vk/VkBufferImageCopy
                 {:imageSubresource (vk/VkImageSubresourceLayers
                                      {:aspectMask vk/VK_IMAGE_ASPECT_COLOR_BIT :layerCount 1})
                  :imageExtent (vk/VkExtent3D {:width width :height height :depth 1})})
        host-barrier (vk/VkBufferMemoryBarrier
                       {:sType vk/VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER
                        :srcAccessMask vk/VK_ACCESS_TRANSFER_WRITE_BIT
                        :dstAccessMask vk/VK_ACCESS_HOST_READ_BIT
                        :srcQueueFamilyIndex vk/VK_QUEUE_FAMILY_IGNORED
                        :dstQueueFamilyIndex vk/VK_QUEUE_FAMILY_IGNORED
                        :buffer buffer :size vk/VK_WHOLE_SIZE})]
    (vk/vkCmdPipelineBarrier command vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                             vk/VK_PIPELINE_STAGE_TRANSFER_BIT 0 0 null 0 null 1 (ak/& barrier))
    (vk/vkCmdCopyImageToBuffer command source vk/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                               buffer 1 (ak/& region))
    (az/set-many!
      (az/field barrier srcAccessMask) vk/VK_ACCESS_TRANSFER_READ_BIT
      (az/field barrier dstAccessMask) 0
      (az/field barrier oldLayout) vk/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
      (az/field barrier newLayout) vk/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
    (vk/vkCmdPipelineBarrier command vk/VK_PIPELINE_STAGE_TRANSFER_BIT
                             (ak/| vk/VK_PIPELINE_STAGE_HOST_BIT vk/VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
                             0 0 null 1 (ak/& host-barrier) 1 (ak/& barrier))))

(az/defn write-frame!
  "After the submission fence: PPM RGB bytes, with the actual mesh frame tag in its header."
  :- :bool
  [[format vk/VkFormat] [frame :u64] [revision :u64] [tick :u64]]
  (let [^:var header-buffer (mem/zeroes (az/type [:array 256 :u8]))
        header (catch (fmt/bufPrint (ak/& header-buffer)
                        "P6\n# pitoco frame={d} revision={d} tick={d} vk_format={d}\n{d} {d}\n255\n"
                        [frame revision tick format width height])
                 (ak/return false))
        pixels (az/cast mapped [:c-pointer :u8])
        bgra (or (ak/== format vk/VK_FORMAT_B8G8R8A8_UNORM)
                 (ak/== format vk/VK_FORMAT_B8G8R8A8_SRGB))
        file (stdio/fopen (ak/& (az/index filename 0)) "wb")]
    (when (ak/== file null) (ak/return false))
    ;; Compact forward only after all four source bytes have been read.
    (dotimes [index (* (ak/as :usize width) height)]
      (let [source (* index 4)
            target (* index 3)
            red (az/index pixels (+ source (if bgra (ak/as :usize 2) 0)))
            green (az/index pixels (+ source 1))
            blue (az/index pixels (+ source (if bgra (ak/as :usize 0) 2)))]
        (az/set-many! (az/index pixels target) red
                      (az/index pixels (+ target 1)) green
                      (az/index pixels (+ target 2)) blue)))
    (let [bytes (* (ak/as :usize width) height 3)
          success (and (ak/== (fwrite (az/field header ptr) 1 (az/field header len) file)
                              (az/field header len))
                       (ak/== (fwrite pixels 1 bytes file) bytes))
          closed (stdio/fclose file)]
      (and success (ak/== closed 0)))))

(az/defn complete!
  :- :void
  [[device vk/VkDevice] [format vk/VkFormat] [frame :u64] [revision :u64] [tick :u64]]
  (let [saved (write-frame! format frame revision tick)]
    (release! device)
    (ak/atomicStore :u8 (ak/& state) (if saved 4 6) :.release)))
