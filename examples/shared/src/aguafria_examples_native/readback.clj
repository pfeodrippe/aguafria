(ns aguafria-examples-native.readback
  "One bounded Vulkan frame readback, owned and completed by the render thread."
  (:refer-clojure :only [])
  (:require [aguafria.std]
            [aguafria.keyword :as ak :refer :all]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as vk]
            [aguafria-examples-native.bindings.runtime :as stdio]))

(a/defextern fwrite :usize
  {:zig/prefix "pub extern"}
  [[data [:*const :anyopaque]] [size :usize] [count :usize]
   [file [:optional [:* stdio/AguafriaFile]]]])

;; 0 idle, 1 producer copying, 2 queued, 3 rendering, 4 saved,
;; 5 unsupported/resource failure, 6 file failure. Terminal states require ack.
(a/defvar state :u8 0)

(a/defvar filename [:array 1024 :u8] (mem/zeroes (a/type [:array 1024 :u8])))

(a/defvar buffer vk/VkBuffer null)

(a/defvar memory vk/VkDeviceMemory null)

(a/defvar mapped [:optional [:* :anyopaque]] null)

(a/defvar width :u32 0)

(a/defvar height :u32 0)

(a/defn status :u8 []
  (atomicLoad :u8 (ak/& state) :acquire))

(a/defn acknowledge! :bool []
  (let [current (status)]
    (and (>= current 4)
         (== (cmpxchgStrong :u8 (ak/& state) current 0 :acq_rel :acquire) null))))

(a/defn request! :bool
  "Copy a NUL-terminated path; reject empty/oversized paths and an occupied slot."
  [[path [:* {:size :c :const? true} :u8]]]
  (when (== path null) (return false))
  (let [^:var length (usize 0)]
    (while (and (< length 1024) (!= (a/index path length) 0))
      (= length (+ length 1)))
    (when (or (== length 0) (>= length 1024)) (return false))
    (when (!= (cmpxchgStrong :u8 (ak/& state) 0 1 :acq_rel :acquire) null)
      (return false))
    (dotimes [index (+ length 1)]
      (= (a/index filename index) (a/index path index)))
    (atomicStore :u8 (ak/& state) 2 :release)
    true))

(a/defn supported-format? :bool [[format vk/VkFormat]]
  (or (== format vk/VK_FORMAT_B8G8R8A8_UNORM)
      (== format vk/VK_FORMAT_B8G8R8A8_SRGB)
      (== format vk/VK_FORMAT_R8G8B8A8_UNORM)
      (== format vk/VK_FORMAT_R8G8B8A8_SRGB)))

(a/defn reject! :void []
  (atomicStore :u8 (ak/& state) 5 :release))

(a/defn release! :void [[device vk/VkDevice]]
  (when (!= mapped null) (vk/vkUnmapMemory device memory))
  (when (!= buffer null) (vk/vkDestroyBuffer device buffer null))
  (when (!= memory null) (vk/vkFreeMemory device memory null))
  (a/set-many! mapped null buffer null memory null))

(a/defn prepare! :bool
  "Render-thread only. Allocate at most 64 MiB; require coherent host memory."
  [[device vk/VkDevice] [physical vk/VkPhysicalDevice] [extent vk/VkExtent2D]]
  (a/set-many! width (a/field extent width) height (a/field extent height))
  (let [bytes (* (as width :u64) height 4)
        info (vk/VkBufferCreateInfo
              {:sType vk/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
               :size bytes :usage vk/VK_BUFFER_USAGE_TRANSFER_DST_BIT
               :sharingMode vk/VK_SHARING_MODE_EXCLUSIVE})
        ^:var requirements (mem/zeroes (a/type vk/VkMemoryRequirements))
        ^:var properties (mem/zeroes (a/type vk/VkPhysicalDeviceMemoryProperties))
        ^:var selected (u32 0xffffffff)]
    (when (or (== bytes 0) (> bytes (* 64 1024 1024))) (return false))
    (when (!= (vk/vkCreateBuffer device (ak/& info) null (ak/& buffer)) vk/VK_SUCCESS)
      (return false))
    (vk/vkGetBufferMemoryRequirements device buffer (ak/& requirements))
    (vk/vkGetPhysicalDeviceMemoryProperties physical (ak/& properties))
    (dotimes [index (a/field properties memoryTypeCount)]
      (let [required (| vk/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT vk/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
            flags (a/field (a/index (a/field properties memoryTypes) index) propertyFlags)]
        (when (and (== selected 0xffffffff)
                   (!= (ak/& (a/field requirements memoryTypeBits)
                             (<< (as 1 :u32) (as (intCast index) :u5))) 0)
                   (== (ak/& flags required) required))
          (= selected (intCast index)))))
    (when (== selected 0xffffffff) (release! device) (return false))
    (let [allocation (vk/VkMemoryAllocateInfo
                      {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
                       :allocationSize (a/field requirements size) :memoryTypeIndex selected})]
      (when (or (!= (vk/vkAllocateMemory device (ak/& allocation) null (ak/& memory)) vk/VK_SUCCESS)
                (!= (vk/vkBindBufferMemory device buffer memory 0) vk/VK_SUCCESS)
                (!= (vk/vkMapMemory device memory 0 bytes 0 (ak/& mapped)) vk/VK_SUCCESS))
        (release! device)
        (return false)))
    (atomicStore :u8 (ak/& state) 3 :release)
    true))

(a/defn record! :void
  "After the color pass: copy to staging, make host reads visible, restore presentation."
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
    (a/set-many!
     (a/field barrier srcAccessMask) vk/VK_ACCESS_TRANSFER_READ_BIT
     (a/field barrier dstAccessMask) 0
     (a/field barrier oldLayout) vk/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
     (a/field barrier newLayout) vk/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
    (vk/vkCmdPipelineBarrier command vk/VK_PIPELINE_STAGE_TRANSFER_BIT
                             (| vk/VK_PIPELINE_STAGE_HOST_BIT vk/VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
                             0 0 null 1 (ak/& host-barrier) 1 (ak/& barrier))))

(a/defn write-frame! :bool
  "After the submission fence: PPM RGB bytes, with the actual mesh frame tag in its header."
  [[format vk/VkFormat] [frame :u64] [revision :u64] [tick :u64]]
  (let [^:var header-buffer (mem/zeroes (a/type [:array 256 :u8]))
        header (ak/catch (mem/print (ak/& header-buffer)
                                    "P6\n# pitoco frame={d} revision={d} tick={d} vk_format={d}\n{d} {d}\n255\n"
                                    [frame revision tick format width height])
                         (return false))
        pixels (a/cast mapped [:c-pointer :u8])
        bgra (or (== format vk/VK_FORMAT_B8G8R8A8_UNORM)
                 (== format vk/VK_FORMAT_B8G8R8A8_SRGB))
        file (stdio/fopen (ak/& (a/index filename 0)) "wb")]
    (when (== file null) (return false))
    ;; Compact forward only after all four source bytes have been read.
    (dotimes [index (* (as width :usize) height)]
      (let [source (* index 4)
            target (* index 3)
            red (a/index pixels (+ source (if bgra (as 2 :usize) 0)))
            green (a/index pixels (+ source 1))
            blue (a/index pixels (+ source (if bgra (as 0 :usize) 2)))]
        (a/set-many! (a/index pixels target) red
                     (a/index pixels (+ target 1)) green
                     (a/index pixels (+ target 2)) blue)))
    (let [bytes (* (as width :usize) height 3)
          success (and (== (fwrite (a/field header ptr) 1 (a/field header len) file)
                           (a/field header len))
                       (== (fwrite pixels 1 bytes file) bytes))
          closed (stdio/fclose file)]
      (and success (== closed 0)))))

(a/defn complete! :void
  [[device vk/VkDevice] [format vk/VkFormat] [frame :u64] [revision :u64] [tick :u64]]
  (let [saved (write-frame! format frame revision tick)]
    (release! device)
    (atomicStore :u8 (ak/& state) (if saved 4 6) :release)))
