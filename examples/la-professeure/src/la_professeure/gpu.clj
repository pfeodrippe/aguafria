(ns la-professeure.gpu
  "Thin Vulkan backend: application-owned mapped memory and GPU-address vertex fetch.
  Adapted from examples/shared; no changes to that backend or other examples."
  (:require [aguafria.std]
            [aguafria.keyword :as k]
            [aguafria.std.debug :as std-debug]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as vk]
            [aguafria-examples-native.bindings.runtime :as stdio]
            [aguafria-examples-native.mesh :as mesh]))

(az/defstruct Color
  {:layout :extern}
  [[:r :f32]
   [:g :f32]
   [:b :f32]
   [:a :f32]])

(az/defconst FrameBuilder
  "Application callback that fills one mapped triangle frame."
  (az/type
   [:*const
    [:fn {:callconv :.c}
     [{:name :output :type [:c-pointer mesh/GpuVertex]}
      {:name :frame-width :type :i32}
      {:name :frame-height :type :i32}]
     :u32]]))

(az/defstruct RendererSnapshot
  "Inspectable state for the live desktop Vulkan renderer."
  {:layout :extern}
  [[:initialized :bool]
   [:frames :u64]
   [:width :u32]
   [:height :u32]
   [:images :u32]
   [:queue_family :u32]])

;; A dense Studio view includes 32 clip waveforms (24,576 vertices before any
;; text or controls), plus the take editor and device picker. The old 16K
;; placeholder budget could not even cover those waveforms. This allocation is
;; shared by the buffer size, atlas offset and checked vertex writer.
(az/defconst frame-capacity :usize 131072)

(az/defconst atlas-bytes :usize (k/* 2048 1536 4))

(az/defstruct RootData {:layout :extern}
  [[:vertices :u64] [:pixels :u64] [:light_x :f32] [:light_y :f32]
   [:lighting :f32] [:reserved :f32]])

(az/defvar vertex-address :u64 0)

(az/defvar light-x :f32 0.4)

(az/defvar light-y :f32 0.4)

(az/defvar lighting :f32 1.0)

(az/defvar initialized false)

(az/defvar renderer-window [:optional [:* vk/GLFWwindow]] nil)

(az/defvar resize-pending :bool false)

(az/defvar resize-count :u64 0)

(az/defvar frame-count :u64 0)

(az/defvar shader-publications :u64 0)

(az/defvar instance vk/VkInstance nil)

(az/defvar surface vk/VkSurfaceKHR nil)

(az/defvar physical-device vk/VkPhysicalDevice nil)

(az/defvar device vk/VkDevice nil)

(az/defvar graphics-queue vk/VkQueue nil)

(az/defvar queue-family :u32 0)

(az/defvar swapchain vk/VkSwapchainKHR nil)

(az/defvar swapchain-format vk/VkFormat vk/VK_FORMAT_B8G8R8A8_UNORM)

(az/defvar swapchain-extent vk/VkExtent2D
  (vk/VkExtent2D {:width 0 :height 0}))

(az/defvar requested-extent vk/VkExtent2D
  (vk/VkExtent2D {:width 0 :height 0}))

(az/defvar image-count :u32 0)

(az/defvar swapchain-images [:array 8 vk/VkImage]
  (std-mem/zeroes [:array 8 vk/VkImage]))

(az/defvar image-views [:array 8 vk/VkImageView]
  (std-mem/zeroes [:array 8 vk/VkImageView]))

(az/defvar depth-image vk/VkImage nil)

(az/defvar depth-memory vk/VkDeviceMemory nil)

(az/defvar depth-view vk/VkImageView nil)

(az/defvar render-pass vk/VkRenderPass nil)

(az/defvar framebuffers [:array 8 vk/VkFramebuffer]
  (std-mem/zeroes [:array 8 vk/VkFramebuffer]))

(az/defvar command-pool vk/VkCommandPool nil)

(az/defvar command-buffers [:array 8 vk/VkCommandBuffer]
  (std-mem/zeroes [:array 8 vk/VkCommandBuffer]))

(az/defvar image-available [:array 2 vk/VkSemaphore]
  (std-mem/zeroes [:array 2 vk/VkSemaphore]))

(az/defvar render-finished [:array 8 vk/VkSemaphore]
  (std-mem/zeroes [:array 8 vk/VkSemaphore]))

(az/defvar in-flight vk/VkFence nil)

(az/defvar synchronization-slot :usize 0)

(az/defvar active-command-buffer vk/VkCommandBuffer nil)

(az/defvar mesh-pipeline vk/VkPipeline nil)

(az/defvar mesh-pipeline-layout vk/VkPipelineLayout nil)

(az/defvar mesh-vertex-buffer vk/VkBuffer nil)

(az/defvar mesh-vertex-memory vk/VkDeviceMemory nil)

(az/defvar mapped-mesh-vertices [:optional [:* :anyopaque]] nil)

(az/defvar mesh-vertex-count :u32 0)

;; Scoped render-thread QA scratch, never part of either window's saved context.
;; Normal frames neither allocate a readback buffer nor wait for a CPU copy.
(az/defvar readback-requested :bool false)

(az/defvar readback-buffer vk/VkBuffer nil)

(az/defvar shader-code [:array 16384 :u32]
  (std-mem/zeroes [:array 16384 :u32]))

;; Render-thread-only contexts. Each window owns its complete Vulkan resource
;; graph, including mapped memory and fences. Swapping CPU handles never moves
;; GPU allocations. The active game context is restored before returning to the
;; main loop. Shader decoding is shared scratch, used only on this same thread.
(def renderer-context-fields
  '[[vertex-address :u64] [light-x :f32] [light-y :f32] [lighting :f32]
    [initialized :bool] [renderer-window [:optional [:* vk/GLFWwindow]]]
    [resize-pending :bool] [resize-count :u64] [frame-count :u64] [instance vk/VkInstance]
    [surface vk/VkSurfaceKHR] [physical-device vk/VkPhysicalDevice]
    [device vk/VkDevice] [graphics-queue vk/VkQueue] [queue-family :u32]
    [swapchain vk/VkSwapchainKHR] [swapchain-format vk/VkFormat]
    [swapchain-extent vk/VkExtent2D] [requested-extent vk/VkExtent2D] [image-count :u32]
    [swapchain-images [:array 8 vk/VkImage]] [image-views [:array 8 vk/VkImageView]]
    [depth-image vk/VkImage] [depth-memory vk/VkDeviceMemory] [depth-view vk/VkImageView]
    [render-pass vk/VkRenderPass] [framebuffers [:array 8 vk/VkFramebuffer]]
    [command-pool vk/VkCommandPool] [command-buffers [:array 8 vk/VkCommandBuffer]]
    [image-available [:array 2 vk/VkSemaphore]] [render-finished [:array 8 vk/VkSemaphore]]
    [in-flight vk/VkFence] [synchronization-slot :usize] [active-command-buffer vk/VkCommandBuffer]
    [mesh-pipeline vk/VkPipeline] [mesh-pipeline-layout vk/VkPipelineLayout]
    [mesh-vertex-buffer vk/VkBuffer] [mesh-vertex-memory vk/VkDeviceMemory]
    [mapped-mesh-vertices [:optional [:* :anyopaque]]] [mesh-vertex-count :u32]])

(eval `(az/defstruct ~'RendererContext ~renderer-context-fields))

(eval `(az/defn ~'swap-context! :void [[~'other [:* ~'RendererContext]]]
         ~@(for [[field _] renderer-context-fields]
             (list 'let ['saved field]
                   (list 'k/= field (list (keyword field) 'other))
                   (list 'k/= (list (keyword field) 'other) 'saved)))))

(az/defn check :void
  "Check Vulkan results even in ReleaseFast."
  [[result vk/VkResult]]
  (when (k/!= result vk/VK_SUCCESS)
    (std-debug/panic "La Professeure Vulkan error: {d}" [result])))

(az/defn initialize-instance! :void
  []
  (let [extension-count (k/var (k/u32 0))
        glfw-extensions (vk/glfwGetRequiredInstanceExtensions (k/& extension-count))
        extensions
        (k/var (std-mem/zeroes
                [:array 8 [:* {:size :c :const? true} :u8]]))]
    (std-debug/assert (k/!= glfw-extensions nil))
    (std-debug/assert (k/< extension-count 8))
    (dotimes [index extension-count]
      (k/= (az/get extensions index) (az/get glfw-extensions index)))
    (k/= (az/get extensions extension-count)
         vk/VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)
    (let [application-info
          (vk/VkApplicationInfo
           {:sType vk/VK_STRUCTURE_TYPE_APPLICATION_INFO
            :pApplicationName "Aguafria native example"
            :applicationVersion 1
            :pEngineName "Aguafria"
            :engineVersion 1
            :apiVersion vk/VK_API_VERSION_1_2})
          create-info
          (vk/VkInstanceCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
            :flags vk/VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
            :pApplicationInfo (k/& application-info)
            :enabledExtensionCount (k/+ extension-count 1)
            :ppEnabledExtensionNames (k/& (az/get extensions 0))})]
      (check (vk/vkCreateInstance (k/& create-info) nil (k/& instance))))))

(az/defn select-device-and-queue! :void
  []
  (let [device-count (k/var (k/u32 0))
        devices (k/var (std-mem/zeroes [:array 8 vk/VkPhysicalDevice]))]
    (check (vk/vkEnumeratePhysicalDevices instance (k/& device-count) nil))
    (std-debug/assert (and (k/> device-count 0) (k/<= device-count 8)))
    (check (vk/vkEnumeratePhysicalDevices
            instance (k/& device-count) (k/& (az/get devices 0))))
    (k/= physical-device (az/get devices 0))
    (let [family-count (k/var (k/u32 0))
          families
          (k/var (std-mem/zeroes [:array 32 vk/VkQueueFamilyProperties]))]
      (vk/vkGetPhysicalDeviceQueueFamilyProperties
       physical-device (k/& family-count) nil)
      (std-debug/assert (and (k/> family-count 0) (k/<= family-count 32)))
      (vk/vkGetPhysicalDeviceQueueFamilyProperties
       physical-device (k/& family-count) (k/& (az/get families 0)))
      (let [family-index (k/var (k/u32 0))
            present-supported (k/var vk/VK_FALSE)]
        (k/while (k/< family-index family-count)
          (k/= present-supported vk/VK_FALSE)
          (check (vk/vkGetPhysicalDeviceSurfaceSupportKHR
                  physical-device family-index surface (k/& present-supported)))
          (when (and
                 (k/!= (k/&
                        (:queueFlags (az/get families family-index))
                        vk/VK_QUEUE_GRAPHICS_BIT)
                       0)
                 (k/== present-supported vk/VK_TRUE))
            (k/= queue-family family-index)
            (k/break))
          (k/= family-index (k/+ family-index 1)))
        (std-debug/assert (k/< family-index family-count))))))

(az/defn create-device! :void
  []
  (let [available
        (k/var (vk/VkPhysicalDeviceVulkan12Features
                {:sType vk/VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES}))
        query
        (k/var (vk/VkPhysicalDeviceFeatures2
                {:sType vk/VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 :pNext (k/& available)}))]
    (vk/vkGetPhysicalDeviceFeatures2 physical-device (k/& query))
    (when (or (k/== (:bufferDeviceAddress available) 0)
              (k/== (:scalarBlockLayout available) 0))
      (std-debug/panic "La Professeure requires Vulkan bufferDeviceAddress and scalarBlockLayout" [])))
  (let [priority (k/f32 1.0)
        queue-info
        (vk/VkDeviceQueueCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
          :queueFamilyIndex queue-family
          :queueCount 1
          :pQueuePriorities (k/& priority)})
        extensions
        (az/array [vk/VK_KHR_SWAPCHAIN_EXTENSION_NAME "VK_KHR_portability_subset"] [:* {:size :c :const? true} :u8])
        features (vk/VkPhysicalDeviceVulkan12Features
                  {:sType vk/VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES
                   :bufferDeviceAddress vk/VK_TRUE :scalarBlockLayout vk/VK_TRUE})
        create-info
        (vk/VkDeviceCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
          :pNext (k/& features)
          :queueCreateInfoCount 1
          :pQueueCreateInfos (k/& queue-info)
          :enabledExtensionCount 2
          :ppEnabledExtensionNames (k/& (az/get extensions 0))})]
    (check (vk/vkCreateDevice physical-device (k/& create-info) nil (k/& device)))
    (vk/vkGetDeviceQueue device queue-family 0 (k/& graphics-queue))))

(az/defn framebuffer-extent vk/VkExtent2D []
  (let [width (k/var (k/as 0 :c_int)) height (k/var (k/as 0 :c_int))]
    (when (k/!= renderer-window nil)
      (vk/glfwGetFramebufferSize renderer-window (k/& width) (k/& height)))
    (vk/VkExtent2D {:width (k/intCast (k/max 0 width))
                    :height (k/intCast (k/max 0 height))})))

(az/defn choose-extent vk/VkExtent2D
  [[capabilities vk/VkSurfaceCapabilitiesKHR] [requested vk/VkExtent2D]]
  ;; Skip minimized drawables without blocking the other window's event loop.
  (when (or (k/== (:width requested) 0) (k/== (:height requested) 0))
    (k/return (vk/VkExtent2D {:width 0 :height 0})))
  (when (k/!= (:width (:currentExtent capabilities)) 0xffffffff)
    (k/return (:currentExtent capabilities)))
  (vk/VkExtent2D
   {:width (k/min (:width (:maxImageExtent capabilities))
                  (k/max (:width (:minImageExtent capabilities))
                         (:width requested)))
    :height (k/min (:height (:maxImageExtent capabilities))
                   (k/max (:height (:minImageExtent capabilities))
                          (:height requested)))}))

(az/defn resize-result? :bool [[result vk/VkResult]]
  (or (k/== result vk/VK_ERROR_OUT_OF_DATE_KHR) (k/== result vk/VK_SUBOPTIMAL_KHR)))

(az/defn create-swapchain! :void
  []
  (let [capabilities
        (k/var (std-mem/zeroes vk/VkSurfaceCapabilitiesKHR))
        format-count (k/var (k/u32 0))
        formats
        (k/var (std-mem/zeroes [:array 128 vk/VkSurfaceFormatKHR]))]
    (check (vk/vkGetPhysicalDeviceSurfaceCapabilitiesKHR
            physical-device surface (k/& capabilities)))
    (check (vk/vkGetPhysicalDeviceSurfaceFormatsKHR
            physical-device surface (k/& format-count) nil))
    (std-debug/assert (and (k/> format-count 0) (k/<= format-count 128)))
    (check (vk/vkGetPhysicalDeviceSurfaceFormatsKHR
            physical-device surface (k/& format-count) (k/& (az/get formats 0))))
    (k/= swapchain-format (:format (az/get formats 0)))
    (k/= requested-extent (framebuffer-extent))
    (k/= swapchain-extent (choose-extent capabilities requested-extent))
    (std-debug/assert (and (k/> (:width swapchain-extent) 0)
                           (k/> (:height swapchain-extent) 0)))
    (let [requested-count (k/+ (:minImageCount capabilities) 1)
          maximum-count (:maxImageCount capabilities)
          actual-count (if (and (k/> maximum-count 0) (k/> requested-count maximum-count))
                         maximum-count
                         requested-count)
          create-info
          (vk/VkSwapchainCreateInfoKHR
           {:sType vk/VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR
            :surface surface
            :minImageCount actual-count
            :imageFormat swapchain-format
            :imageColorSpace (:colorSpace (az/get formats 0))
            :imageExtent swapchain-extent
            :imageArrayLayers 1
            :imageUsage (if readback-requested
                          (k/| vk/VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                               vk/VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                          vk/VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
            :imageSharingMode vk/VK_SHARING_MODE_EXCLUSIVE
            :preTransform (:currentTransform capabilities)
            :compositeAlpha vk/VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
            :presentMode vk/VK_PRESENT_MODE_FIFO_KHR
            :clipped (if readback-requested vk/VK_FALSE vk/VK_TRUE)})]
      (check (vk/vkCreateSwapchainKHR
              device (k/& create-info) nil (k/& swapchain)))
      (check (vk/vkGetSwapchainImagesKHR device swapchain (k/& image-count) nil))
      (std-debug/assert (and (k/> image-count 0) (k/<= image-count 8)))
      (check (vk/vkGetSwapchainImagesKHR
              device swapchain (k/& image-count) (k/& (az/get swapchain-images 0)))))))

(az/defn create-image-views! :void
  []
  (dotimes [index image-count]
    (let [create-info
          (vk/VkImageViewCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
            :image (az/get swapchain-images index)
            :viewType vk/VK_IMAGE_VIEW_TYPE_2D
            :format swapchain-format
            :components
            (vk/VkComponentMapping
             {:r vk/VK_COMPONENT_SWIZZLE_IDENTITY
              :g vk/VK_COMPONENT_SWIZZLE_IDENTITY
              :b vk/VK_COMPONENT_SWIZZLE_IDENTITY
              :a vk/VK_COMPONENT_SWIZZLE_IDENTITY})
            :subresourceRange
            (vk/VkImageSubresourceRange
             {:aspectMask vk/VK_IMAGE_ASPECT_COLOR_BIT
              :baseMipLevel 0
              :levelCount 1
              :baseArrayLayer 0
              :layerCount 1})})]
      (check (vk/vkCreateImageView
              device (k/& create-info) nil (k/& (az/get image-views index)))))))

(az/defn create-render-pass! :void
  []
  (let [attachments
        (az/array [(vk/VkAttachmentDescription
                    {:format swapchain-format
                     :samples vk/VK_SAMPLE_COUNT_1_BIT
                     :loadOp vk/VK_ATTACHMENT_LOAD_OP_CLEAR
                     :storeOp vk/VK_ATTACHMENT_STORE_OP_STORE
                     :stencilLoadOp vk/VK_ATTACHMENT_LOAD_OP_DONT_CARE
                     :stencilStoreOp vk/VK_ATTACHMENT_STORE_OP_DONT_CARE
                     :initialLayout vk/VK_IMAGE_LAYOUT_UNDEFINED
                     :finalLayout vk/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR})
                   (vk/VkAttachmentDescription
                    {:format vk/VK_FORMAT_D32_SFLOAT
                     :samples vk/VK_SAMPLE_COUNT_1_BIT
                     :loadOp vk/VK_ATTACHMENT_LOAD_OP_CLEAR
                     :storeOp vk/VK_ATTACHMENT_STORE_OP_DONT_CARE
                     :stencilLoadOp vk/VK_ATTACHMENT_LOAD_OP_DONT_CARE
                     :stencilStoreOp vk/VK_ATTACHMENT_STORE_OP_DONT_CARE
                     :initialLayout vk/VK_IMAGE_LAYOUT_UNDEFINED
                     :finalLayout vk/VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL})] vk/VkAttachmentDescription)
        color-reference
        (vk/VkAttachmentReference
         {:attachment 0
          :layout vk/VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL})
        depth-reference
        (vk/VkAttachmentReference
         {:attachment 1
          :layout vk/VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL})
        subpass
        (vk/VkSubpassDescription
         {:pipelineBindPoint vk/VK_PIPELINE_BIND_POINT_GRAPHICS
          :colorAttachmentCount 1
          :pColorAttachments (k/& color-reference)
          :pDepthStencilAttachment (k/& depth-reference)})
        dependency
        (vk/VkSubpassDependency
         {:srcSubpass vk/VK_SUBPASS_EXTERNAL
          :dstSubpass 0
          :srcStageMask
          (k/| vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
               vk/VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
          :dstStageMask
          (k/| vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
               vk/VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
          :dstAccessMask
          (k/| vk/VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
               vk/VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)})
        create-info
        (vk/VkRenderPassCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO
          :attachmentCount 2
          :pAttachments (k/& (az/get attachments 0))
          :subpassCount 1
          :pSubpasses (k/& subpass)
          :dependencyCount 1
          :pDependencies (k/& dependency)})]
    (check (vk/vkCreateRenderPass device (k/& create-info) nil (k/& render-pass)))))

(az/defn create-framebuffers! :void
  []
  (dotimes [index image-count]
    (let [attachments
          (az/array [(az/get image-views index) depth-view] vk/VkImageView)
          create-info
          (vk/VkFramebufferCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO
            :renderPass render-pass
            :attachmentCount 2
            :pAttachments (k/& (az/get attachments 0))
            :width (:width swapchain-extent)
            :height (:height swapchain-extent)
            :layers 1})]
      (check (vk/vkCreateFramebuffer
              device (k/& create-info) nil (k/& (az/get framebuffers index)))))))

(az/defn create-commands-and-sync! :void
  []
  (let [pool-info
        (vk/VkCommandPoolCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
          :flags vk/VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
          :queueFamilyIndex queue-family})]
    (check (vk/vkCreateCommandPool device (k/& pool-info) nil (k/& command-pool))))
  (let [allocate-info
        (vk/VkCommandBufferAllocateInfo
         {:sType vk/VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
          :commandPool command-pool
          :level vk/VK_COMMAND_BUFFER_LEVEL_PRIMARY
          :commandBufferCount image-count})]
    (check (vk/vkAllocateCommandBuffers
            device (k/& allocate-info) (k/& (az/get command-buffers 0)))))
  (let [semaphore-info
        (vk/VkSemaphoreCreateInfo {:sType vk/VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO})
        fence-info
        (vk/VkFenceCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
          :flags vk/VK_FENCE_CREATE_SIGNALED_BIT})]
    ;; Acquisition follows the submitted frame; presentation follows the
    ;; acquired image. A submission fence does not prove presentation finished.
    (dotimes [slot 2]
      (check (vk/vkCreateSemaphore
              device (k/& semaphore-info) nil
              (k/& (az/get image-available slot)))))
    (dotimes [index image-count]
      (check (vk/vkCreateSemaphore device (k/& semaphore-info) nil
                                   (k/& (az/get render-finished index)))))
    (check (vk/vkCreateFence device (k/& fence-info) nil (k/& in-flight)))))

(az/defn destroy-commands-and-sync! :void []
  (vk/vkDestroyFence device in-flight nil)
  (dotimes [slot 2]
    (vk/vkDestroySemaphore device (az/get image-available slot) nil))
  (dotimes [index image-count]
    (vk/vkDestroySemaphore device (az/get render-finished index) nil))
  (vk/vkDestroyCommandPool device command-pool nil)
  (k/= active-command-buffer nil)
  (k/= synchronization-slot 0))

(az/defn destroy-swapchain-targets! :void []
  (dotimes [index image-count]
    (vk/vkDestroyFramebuffer device (az/get framebuffers index) nil)
    (vk/vkDestroyImageView device (az/get image-views index) nil))
  (vk/vkDestroyImageView device depth-view nil)
  (vk/vkDestroyImage device depth-image nil)
  (vk/vkFreeMemory device depth-memory nil)
  (vk/vkDestroySwapchainKHR device swapchain nil))

(az/defn find-memory-type :u32
  "Select a physical-device memory type satisfying a Vulkan property mask."
  [[type-bits :u32]
   [required vk/VkMemoryPropertyFlags]]
  (let [properties
        (k/var (std-mem/zeroes vk/VkPhysicalDeviceMemoryProperties))
        selected (k/var (k/u32 0xffffffff))]
    (vk/vkGetPhysicalDeviceMemoryProperties physical-device (k/& properties))
    (dotimes [index (:memoryTypeCount properties)]
      (let [bit (k/<< (k/as 1 :u32)
                      (k/as (k/intCast index) :u5))
            flags (:propertyFlags (az/get (:memoryTypes properties) index))]
        (when (and (k/== selected 0xffffffff)
                   (k/!= (k/& type-bits bit) 0)
                   (k/== (k/& flags required) required))
          (k/= selected (k/intCast index)))))
    selected))

(az/defn create-depth-resources! :void
  "Create the depth attachment shared by the single in-flight frame."
  []
  (let [image-info
        (vk/VkImageCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
          :imageType vk/VK_IMAGE_TYPE_2D
          :format vk/VK_FORMAT_D32_SFLOAT
          :extent
          (vk/VkExtent3D
           {:width (:width swapchain-extent)
            :height (:height swapchain-extent)
            :depth 1})
          :mipLevels 1
          :arrayLayers 1
          :samples vk/VK_SAMPLE_COUNT_1_BIT
          :tiling vk/VK_IMAGE_TILING_OPTIMAL
          :usage vk/VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
          :sharingMode vk/VK_SHARING_MODE_EXCLUSIVE
          :initialLayout vk/VK_IMAGE_LAYOUT_UNDEFINED})
        requirements (k/var (std-mem/zeroes vk/VkMemoryRequirements))]
    (check (vk/vkCreateImage device (k/& image-info) nil (k/& depth-image)))
    (vk/vkGetImageMemoryRequirements device depth-image (k/& requirements))
    (let [memory-type
          (find-memory-type
           (:memoryTypeBits requirements)
           vk/VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
          allocation
          (vk/VkMemoryAllocateInfo
           {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
            :allocationSize (:size requirements)
            :memoryTypeIndex memory-type})]
      (std-debug/assert (k/!= memory-type 0xffffffff))
      (check (vk/vkAllocateMemory device (k/& allocation) nil
                                  (k/& depth-memory)))
      (check (vk/vkBindImageMemory device depth-image depth-memory 0)))
    (let [view-info
          (vk/VkImageViewCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
            :image depth-image
            :viewType vk/VK_IMAGE_VIEW_TYPE_2D
            :format vk/VK_FORMAT_D32_SFLOAT
            :subresourceRange
            (vk/VkImageSubresourceRange
             {:aspectMask vk/VK_IMAGE_ASPECT_DEPTH_BIT
              :baseMipLevel 0
              :levelCount 1
              :baseArrayLayer 0
              :layerCount 1})})]
      (check (vk/vkCreateImageView device (k/& view-info) nil
                                   (k/& depth-view))))))

(az/defn create-mesh-buffer! :void
  "Create one persistently mapped, bounded vertex stream for the 3D scene."
  []
  (let [buffer-size (k/as (k/+ (k/* frame-capacity (k/sizeOf mesh/GpuVertex)) atlas-bytes)
                          vk/VkDeviceSize)
        buffer-info
        (vk/VkBufferCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
          :size buffer-size
          :usage vk/VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
          :sharingMode vk/VK_SHARING_MODE_EXCLUSIVE})
        requirements (k/var (std-mem/zeroes vk/VkMemoryRequirements))]
    (check (vk/vkCreateBuffer device (k/& buffer-info) nil
                              (k/& mesh-vertex-buffer)))
    (vk/vkGetBufferMemoryRequirements device mesh-vertex-buffer
                                      (k/& requirements))
    (let [properties (k/| vk/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                          vk/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
          memory-type (find-memory-type
                       (:memoryTypeBits requirements) properties)
          flags (vk/VkMemoryAllocateFlagsInfo
                 {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO
                  :flags vk/VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT})
          allocate-info
          (vk/VkMemoryAllocateInfo
           {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
            :pNext (k/& flags)
            :allocationSize (:size requirements)
            :memoryTypeIndex memory-type})]
      (std-debug/assert (k/!= memory-type 0xffffffff))
      (check (vk/vkAllocateMemory device (k/& allocate-info) nil
                                  (k/& mesh-vertex-memory)))
      (check (vk/vkBindBufferMemory device mesh-vertex-buffer
                                    mesh-vertex-memory 0))
      (check (vk/vkMapMemory device mesh-vertex-memory 0 buffer-size 0
                             (k/& mapped-mesh-vertices)))
      (let [info (vk/VkBufferDeviceAddressInfo
                  {:sType vk/VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO
                   :buffer mesh-vertex-buffer})]
        (k/= vertex-address (vk/vkGetBufferDeviceAddress device (k/& info)))
        (std-debug/assert (k/> vertex-address 0))))))

(az/defn load-shader-module vk/VkShaderModule
  "Load one checked-in SPIR-V shader and create its Vulkan module."
  [[path [:* {:size :c :const? true} :u8]]]
  (let [file (stdio/fopen path "rb")
        module (k/var (k/as nil vk/VkShaderModule))]
    (when (k/== file nil) (k/return nil))
    (let [bytes (stdio/fread (k/& (az/get shader-code 0))
                             1 (k/* 16384 (k/sizeOf :u32)) file)]
      (k/= :_ (stdio/fclose file))
      (when (or (k/== bytes 0) (k/!= (k/mod bytes 4) 0)) (k/return nil))
      (let [create-info
            (vk/VkShaderModuleCreateInfo
             {:sType vk/VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO
              :codeSize bytes
              :pCode (k/& (az/get shader-code 0))})]
        (when (k/!= (vk/vkCreateShaderModule device (k/& create-info) nil
                                             (k/& module)) vk/VK_SUCCESS)
          (k/return nil))))
    module))

(az/defn create-triangle-pipeline! :bool
  "Prepare a complete replacement; publish only after both stages and pipeline succeed."
  []
  (let [vertex-module (load-shader-module
                       "resources/shaders/mesh.vert.spv")
        fragment-module (load-shader-module "resources/shaders/mesh.frag.spv")
        stages
        (az/array [(vk/VkPipelineShaderStageCreateInfo
                    {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
                     :stage vk/VK_SHADER_STAGE_VERTEX_BIT
                     :module vertex-module
                     :pName "main"})
                   (vk/VkPipelineShaderStageCreateInfo
                    {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
                     :stage vk/VK_SHADER_STAGE_FRAGMENT_BIT
                     :module fragment-module
                     :pName "main"})] vk/VkPipelineShaderStageCreateInfo)
        vertex-input
        (vk/VkPipelineVertexInputStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO})
        input-assembly
        (vk/VkPipelineInputAssemblyStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO
          :topology vk/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
          :primitiveRestartEnable vk/VK_FALSE})
        viewport
        (vk/VkViewport
         {:x 0.0 :y 0.0
          :width (k/as (k/floatFromInt (:width swapchain-extent)) :f32)
          :height (k/as (k/floatFromInt (:height swapchain-extent)) :f32)
          :minDepth 0.0 :maxDepth 1.0})
        scissor
        (vk/VkRect2D {:offset (vk/VkOffset2D {:x 0 :y 0})
                      :extent swapchain-extent})
        viewport-state
        (vk/VkPipelineViewportStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO
          :viewportCount 1 :pViewports (k/& viewport)
          :scissorCount 1 :pScissors (k/& scissor)})
        dynamic-states (az/array [vk/VK_DYNAMIC_STATE_VIEWPORT vk/VK_DYNAMIC_STATE_SCISSOR] vk/VkDynamicState)
        dynamic-state
        (vk/VkPipelineDynamicStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO
          :dynamicStateCount 2 :pDynamicStates (k/& (az/get dynamic-states 0))})
        rasterization
        (vk/VkPipelineRasterizationStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO
          :depthClampEnable vk/VK_FALSE
          :rasterizerDiscardEnable vk/VK_FALSE
          :polygonMode vk/VK_POLYGON_MODE_FILL
          :cullMode vk/VK_CULL_MODE_NONE
          :frontFace vk/VK_FRONT_FACE_COUNTER_CLOCKWISE
          :depthBiasEnable vk/VK_FALSE
          :lineWidth 1.0})
        multisample
        (vk/VkPipelineMultisampleStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO
          :rasterizationSamples vk/VK_SAMPLE_COUNT_1_BIT
          :sampleShadingEnable vk/VK_FALSE})
        depth-stencil
        (vk/VkPipelineDepthStencilStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO
          :depthTestEnable vk/VK_FALSE
          :depthWriteEnable vk/VK_FALSE
          :depthCompareOp vk/VK_COMPARE_OP_LESS
          :depthBoundsTestEnable vk/VK_FALSE
          :stencilTestEnable vk/VK_FALSE
          :minDepthBounds 0.0
          :maxDepthBounds 1.0})
        color-attachment
        (vk/VkPipelineColorBlendAttachmentState
         {:blendEnable vk/VK_TRUE
          :srcColorBlendFactor vk/VK_BLEND_FACTOR_SRC_ALPHA
          :dstColorBlendFactor vk/VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
          :colorBlendOp vk/VK_BLEND_OP_ADD
          :srcAlphaBlendFactor vk/VK_BLEND_FACTOR_ONE
          :dstAlphaBlendFactor vk/VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
          :alphaBlendOp vk/VK_BLEND_OP_ADD
          :colorWriteMask
          (k/| (k/| vk/VK_COLOR_COMPONENT_R_BIT
                    vk/VK_COLOR_COMPONENT_G_BIT)
               (k/| vk/VK_COLOR_COMPONENT_B_BIT
                    vk/VK_COLOR_COMPONENT_A_BIT))})
        color-blend
        (vk/VkPipelineColorBlendStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO
          :logicOpEnable vk/VK_FALSE
          :attachmentCount 1
          :pAttachments (k/& color-attachment)})
        push-range (vk/VkPushConstantRange
                    {:stageFlags (k/| vk/VK_SHADER_STAGE_VERTEX_BIT vk/VK_SHADER_STAGE_FRAGMENT_BIT) :offset 0
                     :size (k/intCast (k/sizeOf RootData))})
        candidate-layout (k/var (k/as nil vk/VkPipelineLayout))
        candidate-pipeline (k/var (k/as nil vk/VkPipeline))
        layout-out (k/& candidate-layout)
        pipeline-out (k/& candidate-pipeline)
        layout-info
        (vk/VkPipelineLayoutCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
          :pushConstantRangeCount 1
          :pPushConstantRanges (k/& push-range)})]
    (k/defer (when (k/!= fragment-module nil) (vk/vkDestroyShaderModule device fragment-module nil)))
    (k/defer (when (k/!= vertex-module nil) (vk/vkDestroyShaderModule device vertex-module nil)))
    (when (or (k/== vertex-module nil) (k/== fragment-module nil)) (k/return false))
    (when (k/!= (vk/vkCreatePipelineLayout device (k/& layout-info) nil
                                           layout-out) vk/VK_SUCCESS) (k/return false))
    (let [pipeline-info
          (vk/VkGraphicsPipelineCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO
            :stageCount 2
            :pStages (k/& (az/get stages 0))
            :pVertexInputState (k/& vertex-input)
            :pInputAssemblyState (k/& input-assembly)
            :pViewportState (k/& viewport-state)
            :pDynamicState (k/& dynamic-state)
            :pRasterizationState (k/& rasterization)
            :pMultisampleState (k/& multisample)
            :pDepthStencilState (k/& depth-stencil)
            :pColorBlendState (k/& color-blend)
            :layout (az/deref layout-out)
            :renderPass render-pass
            :subpass 0})]
      (when (k/!= (vk/vkCreateGraphicsPipelines device nil 1 (k/& pipeline-info)
                                                nil pipeline-out) vk/VK_SUCCESS)
        (when (k/!= candidate-pipeline nil) (vk/vkDestroyPipeline device candidate-pipeline nil))
        (vk/vkDestroyPipelineLayout device candidate-layout nil)
        (k/return false)))
    (k/= mesh-pipeline candidate-pipeline)
    (k/= mesh-pipeline-layout candidate-layout)
    true))

(az/defn create-mesh-pipeline! :void []
  (when (k/! (create-triangle-pipeline!))
    (std-debug/panic "Unable to initialize La Professeure shader pipeline" [])))

(az/defn reload-shaders! :bool
  "Render-thread publication. Failed replacements retain the active pipeline." []
  (when (k/! initialized) (k/return false))
  (check (vk/vkDeviceWaitIdle device))
  (let [old-pipeline mesh-pipeline old-layout mesh-pipeline-layout]
    (if (create-triangle-pipeline!)
      (do (vk/vkDestroyPipeline device old-pipeline nil)
          (vk/vkDestroyPipelineLayout device old-layout nil)
          (k/= shader-publications (k/+ shader-publications 1)) true)
      false)))

(az/defn load-atlas! :void
  "Load packed RGBA asset bytes into our own mapped GPU heap." []
  (let [file (stdio/fopen "resources/demo/atlas.rgba" "rb")
        pixels (az/cast mapped-mesh-vertices [:c-pointer :u8])
        offset (k/* frame-capacity (k/sizeOf mesh/GpuVertex))]
    (when (k/== file nil) (std-debug/panic "Missing resources/demo/atlas.rgba; run :prepare" []))
    (k/defer (k/= :_ (stdio/fclose file)))
    (when (k/!= (stdio/fread (k/& (az/get pixels offset)) 1 atlas-bytes file) atlas-bytes)
      (std-debug/panic "Invalid La Professeure RGBA atlas size; run :prepare" []))))

(az/defn recreate-swapchain! :bool []
  (let [capabilities
        (k/var (k/as (std-mem/zeroes vk/VkSurfaceCapabilitiesKHR) vk/VkSurfaceCapabilitiesKHR))]
    (check (vk/vkGetPhysicalDeviceSurfaceCapabilitiesKHR physical-device surface (k/& capabilities)))
    (let [extent (choose-extent capabilities (framebuffer-extent))]
      (when (or (k/== (:width extent) 0) (k/== (:height extent) 0))
        (k/return false))))
  ;; Keep the device, mapped atlas/vertices and application/audio state intact.
  ;; Base Vulkan uses device-idle retirement here; presentation fences with
  ;; swapchain_maintenance1 are a separate portability/performance improvement.
  (check (vk/vkDeviceWaitIdle device))
  (destroy-commands-and-sync!)
  (destroy-swapchain-targets!)
  (let [old-format swapchain-format]
    (create-swapchain!)
    (create-image-views!)
    (create-depth-resources!)
    (when (k/!= old-format swapchain-format)
      (vk/vkDestroyPipeline device mesh-pipeline nil)
      (vk/vkDestroyPipelineLayout device mesh-pipeline-layout nil)
      (vk/vkDestroyRenderPass device render-pass nil)
      (create-render-pass!)
      (create-mesh-pipeline!)))
  (create-framebuffers!)
  (create-commands-and-sync!)
  (k/= resize-pending false)
  (k/= resize-count (k/+ resize-count 1))
  true)

(az/defn initialize-renderer! :bool
  "Initialize Vulkan against an existing GLFW window."
  [[window [:optional [:* vk/GLFWwindow]]]]
  (when (k/! initialized)
    (k/= renderer-window window)
    (initialize-instance!)
    (check (vk/glfwCreateWindowSurface instance window nil (k/& surface)))
    (select-device-and-queue!)
    (create-device!)
    (create-swapchain!)
    (create-image-views!)
    (create-depth-resources!)
    (create-render-pass!)
    (create-mesh-buffer!)
    (load-atlas!)
    (create-mesh-pipeline!)
    (create-framebuffers!)
    (create-commands-and-sync!)
    (k/= initialized true))
  initialized)

(az/defn clear-value vk/VkClearValue
  [[color Color]]
  (vk/VkClearValue
   {:color
    (vk/VkClearColorValue
     {:float32
      (az/array [(:r color)
                 (:g color)
                 (:b color)
                 (:a color)] :f32)})}))

(az/defn- record-readback! :void
  "Copy the actual color attachment, then restore its presentation layout."
  [[command-buffer vk/VkCommandBuffer]
   [image-index :u32]]
  (when (k/== readback-buffer nil)
    (k/return))
  (let [image (az/get swapchain-images image-index)
        range (vk/VkImageSubresourceRange
               {:aspectMask vk/VK_IMAGE_ASPECT_COLOR_BIT
                :levelCount 1
                :layerCount 1})
        barrier
        (k/var (vk/VkImageMemoryBarrier
                {:sType vk/VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
                 :srcAccessMask vk/VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                 :dstAccessMask vk/VK_ACCESS_TRANSFER_READ_BIT
                 :oldLayout vk/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
                 :newLayout vk/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                 :srcQueueFamilyIndex vk/VK_QUEUE_FAMILY_IGNORED
                 :dstQueueFamilyIndex vk/VK_QUEUE_FAMILY_IGNORED
                 :image image
                 :subresourceRange range}))
        region
        (vk/VkBufferImageCopy
         {:imageSubresource (vk/VkImageSubresourceLayers
                             {:aspectMask vk/VK_IMAGE_ASPECT_COLOR_BIT
                              :layerCount 1})
          :imageExtent (vk/VkExtent3D
                        {:width (:width swapchain-extent)
                         :height (:height swapchain-extent)
                         :depth 1})})
        host-barrier
        (vk/VkBufferMemoryBarrier
         {:sType vk/VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER
          :srcAccessMask vk/VK_ACCESS_TRANSFER_WRITE_BIT
          :dstAccessMask vk/VK_ACCESS_HOST_READ_BIT
          :srcQueueFamilyIndex vk/VK_QUEUE_FAMILY_IGNORED
          :dstQueueFamilyIndex vk/VK_QUEUE_FAMILY_IGNORED
          :buffer readback-buffer
          :size vk/VK_WHOLE_SIZE})]
    (vk/vkCmdPipelineBarrier
     command-buffer vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
     vk/VK_PIPELINE_STAGE_TRANSFER_BIT 0 0 nil 0 nil 1 (k/& barrier))
    (vk/vkCmdCopyImageToBuffer
     command-buffer image vk/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
     readback-buffer 1 (k/& region))
    (k/= (:srcAccessMask barrier) vk/VK_ACCESS_TRANSFER_READ_BIT)
    (k/= (:dstAccessMask barrier) 0)
    (k/= (:oldLayout barrier) vk/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
    (k/= (:newLayout barrier) vk/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
    (vk/vkCmdPipelineBarrier
     command-buffer vk/VK_PIPELINE_STAGE_TRANSFER_BIT
     vk/VK_PIPELINE_STAGE_HOST_BIT 0 0 nil 1 (k/& host-barrier) 1 (k/& barrier))))

(az/defn record-frame :void
  [[image-index :u32]
   [build-frame FrameBuilder]]
  (let [command-buffer (az/get command-buffers image-index)
        begin-info
        (vk/VkCommandBufferBeginInfo
         {:sType vk/VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO})
        background
        (clear-value (Color {:r 0.0 :g 0.0 :b 0.0 :a 1.0}))
        depth-clear
        (vk/VkClearValue
         {:depthStencil (vk/VkClearDepthStencilValue {:depth 1.0 :stencil 0})})
        clear-values
        (az/array [background depth-clear] vk/VkClearValue)
        render-area
        (vk/VkRect2D
         {:offset (vk/VkOffset2D {:x 0 :y 0})
          :extent swapchain-extent})
        pass-info
        (vk/VkRenderPassBeginInfo
         {:sType vk/VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO
          :renderPass render-pass
          :framebuffer (az/get framebuffers image-index)
          :renderArea render-area
          :clearValueCount 2
          :pClearValues (k/& (az/get clear-values 0))})]
    (check (vk/vkResetCommandBuffer command-buffer 0))
    (check (vk/vkBeginCommandBuffer command-buffer (k/& begin-info)))
    (vk/vkCmdBeginRenderPass command-buffer (k/& pass-info) vk/VK_SUBPASS_CONTENTS_INLINE)
    (let [viewport (vk/VkViewport
                    {:x 0.0 :y 0.0
                     :width (k/floatFromInt (:width swapchain-extent))
                     :height (k/floatFromInt (:height swapchain-extent))
                     :minDepth 0.0 :maxDepth 1.0})]
      (vk/vkCmdSetViewport command-buffer 0 1 (k/& viewport))
      (vk/vkCmdSetScissor command-buffer 0 1 (k/& render-area)))
    (k/= active-command-buffer command-buffer)
    (k/= mesh-vertex-count
         (build-frame
          (az/cast mapped-mesh-vertices [:c-pointer mesh/GpuVertex])
          (k/as (k/intCast (:width swapchain-extent)) :i32)
          (k/as (k/intCast (:height swapchain-extent)) :i32)))
    (when (k/> mesh-vertex-count 0)
      (let [root (RootData {:vertices vertex-address
                            :pixels (k/+ vertex-address (k/* frame-capacity (k/sizeOf mesh/GpuVertex)))
                            :light_x light-x :light_y light-y :lighting lighting :reserved 0.0})]
        (vk/vkCmdBindPipeline command-buffer vk/VK_PIPELINE_BIND_POINT_GRAPHICS
                              mesh-pipeline)
        (vk/vkCmdPushConstants command-buffer mesh-pipeline-layout
                               (k/| vk/VK_SHADER_STAGE_VERTEX_BIT vk/VK_SHADER_STAGE_FRAGMENT_BIT)
                               0 (k/intCast (k/sizeOf RootData)) (k/& root))
        (vk/vkCmdDraw command-buffer mesh-vertex-count 1 0 0)))

    (vk/vkCmdEndRenderPass command-buffer)
    (record-readback! command-buffer image-index)
    (check (vk/vkEndCommandBuffer command-buffer))))

(az/defn render! :bool
  "Ask the application for a triangle frame and present it."
  [[build-frame FrameBuilder]]
  (std-debug/assert initialized)
  (let [extent (framebuffer-extent)]
    (when (or (k/== (:width extent) 0) (k/== (:height extent) 0))
      (k/return false))
    (when (or resize-pending
              (k/!= (:width extent) (:width requested-extent))
              (k/!= (:height extent) (:height requested-extent)))
      (when (k/! (recreate-swapchain!)) (k/return false))))
  (let [image-index (k/var (k/u32 0))
        image-ready (az/get image-available synchronization-slot)]
    (check (vk/vkWaitForFences device 1 (k/& in-flight) vk/VK_TRUE vk/VK_WHOLE_SIZE))
    (let [acquired (vk/vkAcquireNextImageKHR
                    device swapchain vk/VK_WHOLE_SIZE image-ready nil (k/& image-index))]
      ;; Leave the fence signaled if no submission will happen this frame.
      (when (k/== acquired vk/VK_ERROR_OUT_OF_DATE_KHR)
        (k/= resize-pending true) (k/return false))
      (if (k/== acquired vk/VK_SUBOPTIMAL_KHR)
        (k/= resize-pending true)
        (check acquired)))
    (do
      (check (vk/vkResetFences device 1 (k/& in-flight)))
      (record-frame image-index build-frame)
      (let [rendering-done (az/get render-finished image-index)
            wait-stage
            (k/u32 (k/intCast vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
            command-buffer (az/get command-buffers image-index)
            submit-info
            (vk/VkSubmitInfo
             {:sType vk/VK_STRUCTURE_TYPE_SUBMIT_INFO
              :waitSemaphoreCount 1
              :pWaitSemaphores (k/& image-ready)
              :pWaitDstStageMask (k/& wait-stage)
              :commandBufferCount 1
              :pCommandBuffers (k/& command-buffer)
              :signalSemaphoreCount 1
              :pSignalSemaphores (k/& rendering-done)})
            present-info
            (vk/VkPresentInfoKHR
             {:sType vk/VK_STRUCTURE_TYPE_PRESENT_INFO_KHR
              :waitSemaphoreCount 1
              :pWaitSemaphores (k/& rendering-done)
              :swapchainCount 1
              :pSwapchains (k/& swapchain)
              :pImageIndices (k/& image-index)})]
        (check (vk/vkQueueSubmit graphics-queue 1 (k/& submit-info) in-flight))
        (let [presented (vk/vkQueuePresentKHR graphics-queue (k/& present-info))]
          (if (resize-result? presented)
            (k/= resize-pending true)
            (check presented)))
        (k/= frame-count (k/+ frame-count 1))
        (k/= synchronization-slot (k/mod (k/+ synchronization-slot 1) 2)))))
  true)

(az/defn- reuse-frame-vertices :u32 {:zig/qualifiers "callconv(.c)"}
  [[output [:c-pointer mesh/GpuVertex]]
   [width :i32]
   [height :i32]]
  (k/= :_ output)
  (k/= :_ width)
  (k/= :_ height)
  mesh-vertex-count)

(az/defn capture-frame! :usize
  "Opt-in render-thread QA. Returns tightly packed BGRA/RGBA bytes, or zero
   when unavailable. Recreates this window's targets without clipping, renders
   the most recent normal frame's mapped vertices through its actual pipeline,
   and waits for GPU completion before copying to output. Does not invoke app
   drawing/input again. Scratch is released; audio/project state is untouched."
  [[output [:c-pointer :u8]]
   [capacity :usize]]
  (when (or (k/! initialized)
            readback-requested
            (k/== mesh-vertex-count 0)
            (k/== output nil)
            (k/== capacity 0))
    (k/return 0))
  (let [capabilities (k/var (std-mem/zeroes vk/VkSurfaceCapabilitiesKHR))]
    (check (vk/vkGetPhysicalDeviceSurfaceCapabilitiesKHR
            physical-device surface (k/& capabilities)))
    (when (k/== (k/& (:supportedUsageFlags capabilities)
                     vk/VK_IMAGE_USAGE_TRANSFER_SRC_BIT) 0)
      (k/return 0)))
  (k/= readback-requested true)
  (k/defer (k/= readback-requested false))
  (when (k/! (recreate-swapchain!))
    (k/return 0))
  (when (and (k/!= swapchain-format vk/VK_FORMAT_B8G8R8A8_UNORM)
             (k/!= swapchain-format vk/VK_FORMAT_B8G8R8A8_SRGB)
             (k/!= swapchain-format vk/VK_FORMAT_R8G8B8A8_UNORM)
             (k/!= swapchain-format vk/VK_FORMAT_R8G8B8A8_SRGB))
    (k/return 0))
  (let [size (k/* (k/as (:width swapchain-extent) :usize)
                  (:height swapchain-extent) 4)
        buffer (k/var (k/as nil vk/VkBuffer))
        memory (k/var (k/as nil vk/VkDeviceMemory))
        mapped (k/var (k/as nil [:optional [:* :anyopaque]]))
        requirements (k/var (std-mem/zeroes vk/VkMemoryRequirements))
        buffer-info (vk/VkBufferCreateInfo
                     {:sType vk/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
                      :size size
                      :usage vk/VK_BUFFER_USAGE_TRANSFER_DST_BIT
                      :sharingMode vk/VK_SHARING_MODE_EXCLUSIVE})]
    (when (or (k/> size capacity) (k/== output nil))
      (k/return 0))
    (check (vk/vkCreateBuffer device (k/& buffer-info) nil (k/& buffer)))
    ;; Destroy the bound buffer before freeing its memory, including early exits.
    (k/defer (do
               (vk/vkDestroyBuffer device buffer nil)
               (when (k/!= memory nil)
                 (vk/vkFreeMemory device memory nil))))
    (vk/vkGetBufferMemoryRequirements device buffer (k/& requirements))
    (let [memory-type (find-memory-type
                       (:memoryTypeBits requirements)
                       (k/| vk/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            vk/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
          allocation (vk/VkMemoryAllocateInfo
                      {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
                       :allocationSize (:size requirements)
                       :memoryTypeIndex memory-type})]
      (when (k/== memory-type 0xffffffff)
        (k/return 0))
      (check (vk/vkAllocateMemory device (k/& allocation) nil (k/& memory))))
    (check (vk/vkBindBufferMemory device buffer memory 0))
    (check (vk/vkMapMemory device memory 0 size 0 (k/& mapped)))
    (k/defer (vk/vkUnmapMemory device memory))
    (k/= readback-buffer buffer)
    (k/defer (k/= readback-buffer nil))
    (when (k/! (render! (k/& reuse-frame-vertices)))
      (k/return 0))
    (check (vk/vkWaitForFences device 1 (k/& in-flight) vk/VK_TRUE vk/VK_WHOLE_SIZE))
    (k/memcpy (az/slice output 0 size)
              (az/slice (az/cast mapped [:c-pointer :u8]) 0 size))
    size))

(az/defn renderer-snapshot RendererSnapshot
  []
  (RendererSnapshot
   {:initialized initialized
    :frames frame-count
    :width (:width swapchain-extent)
    :height (:height swapchain-extent)
    :images image-count
    :queue_family queue-family}))

(az/defn renderer-wait-idle! :void
  []
  (when initialized
    (check (vk/vkDeviceWaitIdle device))))

(az/defn shutdown-renderer! :void
  "Destroy desktop Vulkan resources in dependency order."
  []
  (when initialized
    (renderer-wait-idle!)
    (vk/vkDestroyPipeline device mesh-pipeline nil)
    (vk/vkDestroyPipelineLayout device mesh-pipeline-layout nil)
    (vk/vkUnmapMemory device mesh-vertex-memory)
    (vk/vkDestroyBuffer device mesh-vertex-buffer nil)
    (vk/vkFreeMemory device mesh-vertex-memory nil)
    (destroy-commands-and-sync!)
    (destroy-swapchain-targets!)
    (vk/vkDestroyRenderPass device render-pass nil)
    (vk/vkDestroyDevice device nil)
    (vk/vkDestroySurfaceKHR instance surface nil)
    (vk/vkDestroyInstance instance nil)
    (k/= initialized false)
    (k/= renderer-window nil)
    (k/= resize-pending false)
    (k/= resize-count 0)
    (k/= frame-count 0)
    (k/= image-count 0)
    (k/= instance nil)
    (k/= surface nil)
    (k/= physical-device nil)
    (k/= device nil)
    (k/= graphics-queue nil)
    (k/= swapchain nil)
    (k/= depth-image nil)
    (k/= depth-memory nil)
    (k/= depth-view nil)
    (k/= render-pass nil)
    (k/= mesh-pipeline nil)
    (k/= mesh-pipeline-layout nil)
    (k/= mesh-vertex-buffer nil)
    (k/= mesh-vertex-memory nil)
    (k/= mapped-mesh-vertices nil)
    (k/= mesh-vertex-count 0)
    (k/= active-command-buffer nil)
    (k/= command-pool nil)
    (k/= image-available
         (std-mem/zeroes [:array 2 vk/VkSemaphore]))
    (k/= render-finished
         (std-mem/zeroes [:array 8 vk/VkSemaphore]))
    (k/= synchronization-slot 0)
    (k/= in-flight nil)))
