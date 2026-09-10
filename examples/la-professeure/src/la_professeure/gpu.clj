(ns la-professeure.gpu
  "Thin Vulkan backend: application-owned mapped memory and GPU-address vertex fetch.
  Adapted from examples/shared; no changes to that backend or other examples."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
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
     [{:name output :type [:c-pointer mesh/GpuVertex]}
      {:name frame_width :type :i32}
      {:name frame_height :type :i32}]
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

(az/defconst frame-capacity :usize 16384)

(az/defconst atlas-bytes :usize (* 2048 1536 4))

(az/defstruct RootData {:layout :extern}
  [[:vertices :u64] [:pixels :u64] [:light_x :f32] [:light_y :f32]
   [:lighting :f32] [:reserved :f32]])

(az/defvar vertex-address :u64 0)

(az/defvar light-x :f32 0.4)

(az/defvar light-y :f32 0.4)

(az/defvar lighting :f32 1.0)

(az/defvar initialized false)
(az/defvar renderer-window [:optional [:* vk/GLFWwindow]] null)
(az/defvar resize-pending :bool false)
(az/defvar resize-count :u64 0)

(az/defvar frame-count :u64 0)
(az/defvar shader-publications :u64 0)

(az/defvar instance vk/VkInstance null)

(az/defvar surface vk/VkSurfaceKHR null)

(az/defvar physical-device vk/VkPhysicalDevice null)

(az/defvar device vk/VkDevice null)

(az/defvar graphics-queue vk/VkQueue null)

(az/defvar queue-family :u32 0)

(az/defvar swapchain vk/VkSwapchainKHR null)

(az/defvar swapchain-format vk/VkFormat vk/VK_FORMAT_B8G8R8A8_UNORM)

(az/defvar swapchain-extent vk/VkExtent2D
  (vk/VkExtent2D {:width 0 :height 0}))
(az/defvar requested-extent vk/VkExtent2D
  (vk/VkExtent2D {:width 0 :height 0}))

(az/defvar image-count :u32 0)

(az/defvar swapchain-images [:array 8 vk/VkImage]
  (std-mem/zeroes (az/type [:array 8 vk/VkImage])))

(az/defvar image-views [:array 8 vk/VkImageView]
  (std-mem/zeroes (az/type [:array 8 vk/VkImageView])))

(az/defvar depth-image vk/VkImage null)

(az/defvar depth-memory vk/VkDeviceMemory null)

(az/defvar depth-view vk/VkImageView null)

(az/defvar render-pass vk/VkRenderPass null)

(az/defvar framebuffers [:array 8 vk/VkFramebuffer]
  (std-mem/zeroes (az/type [:array 8 vk/VkFramebuffer])))

(az/defvar command-pool vk/VkCommandPool null)

(az/defvar command-buffers [:array 8 vk/VkCommandBuffer]
  (std-mem/zeroes (az/type [:array 8 vk/VkCommandBuffer])))

(az/defvar image-available [:array 2 vk/VkSemaphore]
  (std-mem/zeroes (az/type [:array 2 vk/VkSemaphore])))

(az/defvar render-finished [:array 8 vk/VkSemaphore]
  (std-mem/zeroes (az/type [:array 8 vk/VkSemaphore])))

(az/defvar in-flight vk/VkFence null)

(az/defvar synchronization-slot :usize 0)

(az/defvar active-command-buffer vk/VkCommandBuffer null)

(az/defvar mesh-pipeline vk/VkPipeline null)

(az/defvar mesh-pipeline-layout vk/VkPipelineLayout null)

(az/defvar mesh-vertex-buffer vk/VkBuffer null)

(az/defvar mesh-vertex-memory vk/VkDeviceMemory null)

(az/defvar mapped-mesh-vertices [:optional [:* :anyopaque]] null)

(az/defvar mesh-vertex-count :u32 0)

(az/defvar shader-code [:array 16384 :u32]
  (std-mem/zeroes (az/type [:array 16384 :u32])))

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
(eval `(az/defn ~'swap-context! :- :void [[~'other [:* ~'RendererContext]]]
         ~@(for [[field _] renderer-context-fields]
             (list 'let ['saved field]
                   (list 'set! field (list 'az/field 'other field))
                   (list 'set! (list 'az/field 'other field) 'saved)))))

(az/defn check
  "Check Vulkan results even in ReleaseFast."
  :- :void
  [[result vk/VkResult]]
  (when (ak/!= result vk/VK_SUCCESS)
    (std-debug/panic "La Professeure Vulkan error: {d}" [result])))

(az/defn initialize-instance!
  :- :void
  []
  (let [^{:var true :zig/type :u32} extension-count 0
        glfw-extensions (vk/glfwGetRequiredInstanceExtensions (ak/& extension-count))
        ^:var extensions
        (std-mem/zeroes
         (az/type [:array 8 [:pointer {:size :c :const? true} :u8]]))]
    (std-debug/assert (ak/!= glfw-extensions null))
    (std-debug/assert (< extension-count 8))
    (dotimes [index extension-count]
      (set! (az/index extensions index) (az/index glfw-extensions index)))
    (set! (az/index extensions extension-count)
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
            :pApplicationInfo (ak/& application-info)
            :enabledExtensionCount (+ extension-count 1)
            :ppEnabledExtensionNames (ak/& (az/index extensions 0))})]
      (check (vk/vkCreateInstance (ak/& create-info) null (ak/& instance))))))

(az/defn select-device-and-queue!
  :- :void
  []
  (let [^{:var true :zig/type :u32} device-count 0
        ^:var devices (std-mem/zeroes (az/type [:array 8 vk/VkPhysicalDevice]))]
    (check (vk/vkEnumeratePhysicalDevices instance (ak/& device-count) null))
    (std-debug/assert (and (> device-count 0) (<= device-count 8)))
    (check (vk/vkEnumeratePhysicalDevices
            instance (ak/& device-count) (ak/& (az/index devices 0))))
    (set! physical-device (az/index devices 0))
    (let [^{:var true :zig/type :u32} family-count 0
          ^:var families
          (std-mem/zeroes (az/type [:array 32 vk/VkQueueFamilyProperties]))]
      (vk/vkGetPhysicalDeviceQueueFamilyProperties
       physical-device (ak/& family-count) null)
      (std-debug/assert (and (> family-count 0) (<= family-count 32)))
      (vk/vkGetPhysicalDeviceQueueFamilyProperties
       physical-device (ak/& family-count) (ak/& (az/index families 0)))
      (let [^{:var true :zig/type :u32} family-index 0
            ^:var present-supported vk/VK_FALSE]
        (ak/while (< family-index family-count)
          (set! present-supported vk/VK_FALSE)
          (check (vk/vkGetPhysicalDeviceSurfaceSupportKHR
                  physical-device family-index surface (ak/& present-supported)))
          (when (and
                 (ak/!= (ak/&
                      (az/field (az/index families family-index) queueFlags)
                      vk/VK_QUEUE_GRAPHICS_BIT)
                     0)
                 (ak/== present-supported vk/VK_TRUE))
            (set! queue-family family-index)
            (ak/break))
          (set! family-index (+ family-index 1)))
        (std-debug/assert (< family-index family-count))))))

(az/defn create-device!
  :- :void
  []
  (let [^{:var true} available
        (vk/VkPhysicalDeviceVulkan12Features
          {:sType vk/VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES})
        ^{:var true} query
        (vk/VkPhysicalDeviceFeatures2
          {:sType vk/VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 :pNext (ak/& available)})]
    (vk/vkGetPhysicalDeviceFeatures2 physical-device (ak/& query))
    (when (or (ak/== (az/field available bufferDeviceAddress) 0)
              (ak/== (az/field available scalarBlockLayout) 0))
      (std-debug/panic "La Professeure requires Vulkan bufferDeviceAddress and scalarBlockLayout" [])))
  (let [^{:zig/type :f32} priority 1.0
        queue-info
        (vk/VkDeviceQueueCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
          :queueFamilyIndex queue-family
          :queueCount 1
          :pQueuePriorities (ak/& priority)})
        extensions
        (az/array-init
         [:array 2 [:pointer {:size :c :const? true} :u8]]
         [vk/VK_KHR_SWAPCHAIN_EXTENSION_NAME "VK_KHR_portability_subset"])
        features (vk/VkPhysicalDeviceVulkan12Features
                   {:sType vk/VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES
                    :bufferDeviceAddress vk/VK_TRUE :scalarBlockLayout vk/VK_TRUE})
        create-info
        (vk/VkDeviceCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
          :pNext (ak/& features)
          :queueCreateInfoCount 1
          :pQueueCreateInfos (ak/& queue-info)
          :enabledExtensionCount 2
          :ppEnabledExtensionNames (ak/& (az/index extensions 0))})]
    (check (vk/vkCreateDevice physical-device (ak/& create-info) null (ak/& device)))
    (vk/vkGetDeviceQueue device queue-family 0 (ak/& graphics-queue))))

(az/defn framebuffer-extent :- vk/VkExtent2D []
  (let [^{:var :c_int} width 0 ^{:var :c_int} height 0]
    (when (ak/!= renderer-window null)
      (vk/glfwGetFramebufferSize renderer-window (ak/& width) (ak/& height)))
    (vk/VkExtent2D {:width (ak/intCast (ak/max 0 width))
                   :height (ak/intCast (ak/max 0 height))})))

(az/defn choose-extent :- vk/VkExtent2D
  [[capabilities vk/VkSurfaceCapabilitiesKHR] [requested vk/VkExtent2D]]
  ;; Skip minimized drawables without blocking the other window's event loop.
  (when (or (ak/== (az/field requested width) 0) (ak/== (az/field requested height) 0))
    (ak/return (vk/VkExtent2D {:width 0 :height 0})))
  (when (ak/!= (az/field (az/field capabilities currentExtent) width) 0xffffffff)
    (ak/return (az/field capabilities currentExtent)))
  (vk/VkExtent2D
    {:width (ak/min (az/field (az/field capabilities maxImageExtent) width)
                   (ak/max (az/field (az/field capabilities minImageExtent) width)
                           (az/field requested width)))
     :height (ak/min (az/field (az/field capabilities maxImageExtent) height)
                    (ak/max (az/field (az/field capabilities minImageExtent) height)
                            (az/field requested height)))}))

(az/defn resize-result? :- :bool [[result vk/VkResult]]
  (or (ak/== result vk/VK_ERROR_OUT_OF_DATE_KHR) (ak/== result vk/VK_SUBOPTIMAL_KHR)))

(az/defn create-swapchain!
  :- :void
  []
  (let [^:var capabilities
        (std-mem/zeroes (az/type vk/VkSurfaceCapabilitiesKHR))
        ^{:var true :zig/type :u32} format-count 0
        ^:var formats
        (std-mem/zeroes (az/type [:array 128 vk/VkSurfaceFormatKHR]))]
    (check (vk/vkGetPhysicalDeviceSurfaceCapabilitiesKHR
            physical-device surface (ak/& capabilities)))
    (check (vk/vkGetPhysicalDeviceSurfaceFormatsKHR
            physical-device surface (ak/& format-count) null))
    (std-debug/assert (and (> format-count 0) (<= format-count 128)))
    (check (vk/vkGetPhysicalDeviceSurfaceFormatsKHR
            physical-device surface (ak/& format-count) (ak/& (az/index formats 0))))
    (set! swapchain-format (az/field (az/index formats 0) format))
    (set! requested-extent (framebuffer-extent))
    (set! swapchain-extent (choose-extent capabilities requested-extent))
    (std-debug/assert (and (> (az/field swapchain-extent width) 0)
                           (> (az/field swapchain-extent height) 0)))
    (let [requested-count (+ (az/field capabilities minImageCount) 1)
          maximum-count (az/field capabilities maxImageCount)
          actual-count (if (and (> maximum-count 0) (> requested-count maximum-count))
                         maximum-count
                         requested-count)
          create-info
          (vk/VkSwapchainCreateInfoKHR
           {:sType vk/VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR
            :surface surface
            :minImageCount actual-count
            :imageFormat swapchain-format
            :imageColorSpace (az/field (az/index formats 0) colorSpace)
            :imageExtent swapchain-extent
            :imageArrayLayers 1
            :imageUsage vk/VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
            :imageSharingMode vk/VK_SHARING_MODE_EXCLUSIVE
            :preTransform (az/field capabilities currentTransform)
            :compositeAlpha vk/VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
            :presentMode vk/VK_PRESENT_MODE_FIFO_KHR
            :clipped vk/VK_TRUE})]
      (check (vk/vkCreateSwapchainKHR
              device (ak/& create-info) null (ak/& swapchain)))
      (check (vk/vkGetSwapchainImagesKHR device swapchain (ak/& image-count) null))
      (std-debug/assert (and (> image-count 0) (<= image-count 8)))
      (check (vk/vkGetSwapchainImagesKHR
              device swapchain (ak/& image-count) (ak/& (az/index swapchain-images 0)))))))

(az/defn create-image-views!
  :- :void
  []
  (dotimes [index image-count]
    (let [create-info
          (vk/VkImageViewCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
            :image (az/index swapchain-images index)
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
              device (ak/& create-info) null (ak/& (az/index image-views index)))))))

(az/defn create-render-pass!
  :- :void
  []
  (let [attachments
        (az/array-init
         [:array 2 vk/VkAttachmentDescription]
         [(vk/VkAttachmentDescription
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
            :finalLayout vk/VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL})])
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
          :pColorAttachments (ak/& color-reference)
          :pDepthStencilAttachment (ak/& depth-reference)})
        dependency
        (vk/VkSubpassDependency
         {:srcSubpass vk/VK_SUBPASS_EXTERNAL
          :dstSubpass 0
          :srcStageMask
          (ak/| vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                vk/VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
          :dstStageMask
          (ak/| vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                vk/VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
          :dstAccessMask
          (ak/| vk/VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                vk/VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)})
        create-info
        (vk/VkRenderPassCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO
          :attachmentCount 2
          :pAttachments (ak/& (az/index attachments 0))
          :subpassCount 1
          :pSubpasses (ak/& subpass)
          :dependencyCount 1
          :pDependencies (ak/& dependency)})]
    (check (vk/vkCreateRenderPass device (ak/& create-info) null (ak/& render-pass)))))

(az/defn create-framebuffers!
  :- :void
  []
  (dotimes [index image-count]
    (let [attachments
          (az/array-init [:array 2 vk/VkImageView]
                         [(az/index image-views index) depth-view])
          create-info
          (vk/VkFramebufferCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO
            :renderPass render-pass
            :attachmentCount 2
            :pAttachments (ak/& (az/index attachments 0))
            :width (az/field swapchain-extent width)
            :height (az/field swapchain-extent height)
            :layers 1})]
      (check (vk/vkCreateFramebuffer
              device (ak/& create-info) null (ak/& (az/index framebuffers index)))))))

(az/defn create-commands-and-sync!
  :- :void
  []
  (let [pool-info
        (vk/VkCommandPoolCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
          :flags vk/VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
          :queueFamilyIndex queue-family})]
    (check (vk/vkCreateCommandPool device (ak/& pool-info) null (ak/& command-pool))))
  (let [allocate-info
        (vk/VkCommandBufferAllocateInfo
         {:sType vk/VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
          :commandPool command-pool
          :level vk/VK_COMMAND_BUFFER_LEVEL_PRIMARY
          :commandBufferCount image-count})]
    (check (vk/vkAllocateCommandBuffers
            device (ak/& allocate-info) (ak/& (az/index command-buffers 0)))))
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
              device (ak/& semaphore-info) null
              (ak/& (az/index image-available slot)))))
    (dotimes [index image-count]
      (check (vk/vkCreateSemaphore device (ak/& semaphore-info) null
                                   (ak/& (az/index render-finished index)))))
    (check (vk/vkCreateFence device (ak/& fence-info) null (ak/& in-flight)))))

(az/defn destroy-commands-and-sync! :- :void []
  (vk/vkDestroyFence device in-flight null)
  (dotimes [slot 2]
    (vk/vkDestroySemaphore device (az/index image-available slot) null))
  (dotimes [index image-count]
    (vk/vkDestroySemaphore device (az/index render-finished index) null))
  (vk/vkDestroyCommandPool device command-pool null)
  (set! active-command-buffer null)
  (set! synchronization-slot 0))

(az/defn destroy-swapchain-targets! :- :void []
  (dotimes [index image-count]
    (vk/vkDestroyFramebuffer device (az/index framebuffers index) null)
    (vk/vkDestroyImageView device (az/index image-views index) null))
  (vk/vkDestroyImageView device depth-view null)
  (vk/vkDestroyImage device depth-image null)
  (vk/vkFreeMemory device depth-memory null)
  (vk/vkDestroySwapchainKHR device swapchain null))

(az/defn find-memory-type
  "Select a physical-device memory type satisfying a Vulkan property mask."
  :-
  :u32
  [[type-bits :u32]
   [required vk/VkMemoryPropertyFlags]]
  (let [^{:var true}
        properties
        (std-mem/zeroes (az/type vk/VkPhysicalDeviceMemoryProperties))
        ^{:var true :zig/type :u32} selected 0xffffffff]
    (vk/vkGetPhysicalDeviceMemoryProperties physical-device (ak/& properties))
    (dotimes [index (az/field properties memoryTypeCount)]
      (let [bit (ak/<< (ak/as :u32 1)
                       (ak/as :u5 (ak/intCast index)))
            flags (az/field (az/index (az/field properties memoryTypes) index)
                            propertyFlags)]
        (when (and (ak/== selected 0xffffffff)
                   (ak/!= (ak/& type-bits bit) 0)
                   (ak/== (ak/& flags required) required))
          (set! selected (ak/intCast index)))))
    selected))

(az/defn create-depth-resources!
  "Create the depth attachment shared by the single in-flight frame."
  :-
  :void
  []
  (let [image-info
        (vk/VkImageCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
          :imageType vk/VK_IMAGE_TYPE_2D
          :format vk/VK_FORMAT_D32_SFLOAT
          :extent
          (vk/VkExtent3D
           {:width (az/field swapchain-extent width)
            :height (az/field swapchain-extent height)
            :depth 1})
          :mipLevels 1
          :arrayLayers 1
          :samples vk/VK_SAMPLE_COUNT_1_BIT
          :tiling vk/VK_IMAGE_TILING_OPTIMAL
          :usage vk/VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
          :sharingMode vk/VK_SHARING_MODE_EXCLUSIVE
          :initialLayout vk/VK_IMAGE_LAYOUT_UNDEFINED})
        ^{:var true}
        requirements (std-mem/zeroes (az/type vk/VkMemoryRequirements))]
    (check (vk/vkCreateImage device (ak/& image-info) null (ak/& depth-image)))
    (vk/vkGetImageMemoryRequirements device depth-image (ak/& requirements))
    (let [memory-type
          (find-memory-type
           (az/field requirements memoryTypeBits)
           vk/VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
          allocation
          (vk/VkMemoryAllocateInfo
           {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
            :allocationSize (az/field requirements size)
            :memoryTypeIndex memory-type})]
      (std-debug/assert (ak/!= memory-type 0xffffffff))
      (check (vk/vkAllocateMemory device (ak/& allocation) null
                                  (ak/& depth-memory)))
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
      (check (vk/vkCreateImageView device (ak/& view-info) null
                                  (ak/& depth-view))))))

(az/defn create-mesh-buffer!
  "Create one persistently mapped, bounded vertex stream for the 3D scene."
  :-
  :void
  []
  (let [buffer-size (ak/as vk/VkDeviceSize
                           (+ (* frame-capacity (ak/sizeOf mesh/GpuVertex)) atlas-bytes))
        buffer-info
        (vk/VkBufferCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
          :size buffer-size
          :usage vk/VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
          :sharingMode vk/VK_SHARING_MODE_EXCLUSIVE})
        ^{:var true}
        requirements (std-mem/zeroes (az/type vk/VkMemoryRequirements))]
    (check (vk/vkCreateBuffer device (ak/& buffer-info) null
                              (ak/& mesh-vertex-buffer)))
    (vk/vkGetBufferMemoryRequirements device mesh-vertex-buffer
                                      (ak/& requirements))
    (let [properties (ak/| vk/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            vk/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
          memory-type (find-memory-type
                       (az/field requirements memoryTypeBits) properties)
          flags (vk/VkMemoryAllocateFlagsInfo
                 {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO
                  :flags vk/VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT})
          allocate-info
          (vk/VkMemoryAllocateInfo
           {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
            :pNext (ak/& flags)
            :allocationSize (az/field requirements size)
            :memoryTypeIndex memory-type})]
      (std-debug/assert (ak/!= memory-type 0xffffffff))
      (check (vk/vkAllocateMemory device (ak/& allocate-info) null
                                  (ak/& mesh-vertex-memory)))
      (check (vk/vkBindBufferMemory device mesh-vertex-buffer
                                    mesh-vertex-memory 0))
      (check (vk/vkMapMemory device mesh-vertex-memory 0 buffer-size 0
                            (ak/& mapped-mesh-vertices)))
      (let [info (vk/VkBufferDeviceAddressInfo
                   {:sType vk/VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO
                    :buffer mesh-vertex-buffer})]
        (set! vertex-address (vk/vkGetBufferDeviceAddress device (ak/& info)))
        (std-debug/assert (> vertex-address 0))))))

(az/defn load-shader-module
  "Load one checked-in SPIR-V shader and create its Vulkan module."
  :-
  vk/VkShaderModule
  [[path [:pointer {:size :c :const? true} :u8]]]
  (let [file (stdio/fopen path "rb")
        ^{:var true} module (ak/as vk/VkShaderModule null)]
    (when (ak/== file null) (ak/return null))
    (let [bytes (stdio/fread (ak/& (az/index shader-code 0))
                              1 (* 16384 (ak/sizeOf :u32)) file)]
      (set! _ (stdio/fclose file))
      (when (or (ak/== bytes 0) (ak/!= (mod bytes 4) 0)) (ak/return null))
      (let [create-info
            (vk/VkShaderModuleCreateInfo
             {:sType vk/VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO
              :codeSize bytes
              :pCode (ak/& (az/index shader-code 0))})]
        (when (ak/!= (vk/vkCreateShaderModule device (ak/& create-info) null
                                        (ak/& module)) vk/VK_SUCCESS)
          (ak/return null))))
    module))

(az/defn create-triangle-pipeline!
  "Prepare a complete replacement; publish only after both stages and pipeline succeed."
  :-
  :bool
  []
  (let [vertex-module (load-shader-module
                       "resources/shaders/mesh.vert.spv")
        fragment-module (load-shader-module "resources/shaders/mesh.frag.spv")
        stages
        (az/array-init
         [:array 2 vk/VkPipelineShaderStageCreateInfo]
         [(vk/VkPipelineShaderStageCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
            :stage vk/VK_SHADER_STAGE_VERTEX_BIT
            :module vertex-module
            :pName "main"})
          (vk/VkPipelineShaderStageCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
            :stage vk/VK_SHADER_STAGE_FRAGMENT_BIT
            :module fragment-module
            :pName "main"})])
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
          :width (ak/as :f32 (ak/floatFromInt (az/field swapchain-extent width)))
          :height (ak/as :f32 (ak/floatFromInt (az/field swapchain-extent height)))
          :minDepth 0.0 :maxDepth 1.0})
        scissor
        (vk/VkRect2D {:offset (vk/VkOffset2D {:x 0 :y 0})
                      :extent swapchain-extent})
        viewport-state
        (vk/VkPipelineViewportStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO
          :viewportCount 1 :pViewports (ak/& viewport)
          :scissorCount 1 :pScissors (ak/& scissor)})
        dynamic-states (az/array-init [:array 2 vk/VkDynamicState]
                         [vk/VK_DYNAMIC_STATE_VIEWPORT vk/VK_DYNAMIC_STATE_SCISSOR])
        dynamic-state
        (vk/VkPipelineDynamicStateCreateInfo
          {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO
           :dynamicStateCount 2 :pDynamicStates (ak/& (az/index dynamic-states 0))})
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
          (ak/| (ak/| vk/VK_COLOR_COMPONENT_R_BIT
                       vk/VK_COLOR_COMPONENT_G_BIT)
                (ak/| vk/VK_COLOR_COMPONENT_B_BIT
                       vk/VK_COLOR_COMPONENT_A_BIT))})
        color-blend
        (vk/VkPipelineColorBlendStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO
          :logicOpEnable vk/VK_FALSE
          :attachmentCount 1
          :pAttachments (ak/& color-attachment)})
        push-range (vk/VkPushConstantRange
                     {:stageFlags (ak/| vk/VK_SHADER_STAGE_VERTEX_BIT vk/VK_SHADER_STAGE_FRAGMENT_BIT) :offset 0
                      :size (ak/intCast (ak/sizeOf RootData))})
        ^{:var true} candidate-layout (ak/as vk/VkPipelineLayout null)
        ^{:var true} candidate-pipeline (ak/as vk/VkPipeline null)
        layout-out (ak/& candidate-layout)
        pipeline-out (ak/& candidate-pipeline)
        layout-info
        (vk/VkPipelineLayoutCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
          :pushConstantRangeCount 1
          :pPushConstantRanges (ak/& push-range)})]
    (ak/defer (when (ak/!= fragment-module null) (vk/vkDestroyShaderModule device fragment-module null)))
    (ak/defer (when (ak/!= vertex-module null) (vk/vkDestroyShaderModule device vertex-module null)))
    (when (or (ak/== vertex-module null) (ak/== fragment-module null)) (ak/return false))
    (when (ak/!= (vk/vkCreatePipelineLayout device (ak/& layout-info) null
                                      layout-out) vk/VK_SUCCESS) (ak/return false))
    (let [pipeline-info
          (vk/VkGraphicsPipelineCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO
            :stageCount 2
            :pStages (ak/& (az/index stages 0))
            :pVertexInputState (ak/& vertex-input)
            :pInputAssemblyState (ak/& input-assembly)
            :pViewportState (ak/& viewport-state)
            :pDynamicState (ak/& dynamic-state)
            :pRasterizationState (ak/& rasterization)
            :pMultisampleState (ak/& multisample)
            :pDepthStencilState (ak/& depth-stencil)
            :pColorBlendState (ak/& color-blend)
            :layout (az/deref layout-out)
            :renderPass render-pass
            :subpass 0})]
      (when (ak/!= (vk/vkCreateGraphicsPipelines device null 1 (ak/& pipeline-info)
                                           null pipeline-out) vk/VK_SUCCESS)
        (when (ak/!= candidate-pipeline null) (vk/vkDestroyPipeline device candidate-pipeline null))
        (vk/vkDestroyPipelineLayout device candidate-layout null)
        (ak/return false)))
    (set! mesh-pipeline candidate-pipeline)
    (set! mesh-pipeline-layout candidate-layout)
    true))

(az/defn create-mesh-pipeline! :- :void []
  (when (ak/! (create-triangle-pipeline!))
    (std-debug/panic "Unable to initialize La Professeure shader pipeline" [])))

(az/defn reload-shaders!
  "Render-thread publication. Failed replacements retain the active pipeline."
  :- :bool []
  (when (ak/! initialized) (ak/return false))
  (check (vk/vkDeviceWaitIdle device))
  (let [old-pipeline mesh-pipeline old-layout mesh-pipeline-layout]
    (if (create-triangle-pipeline!)
      (do (vk/vkDestroyPipeline device old-pipeline null)
          (vk/vkDestroyPipelineLayout device old-layout null)
          (set! shader-publications (+ shader-publications 1)) true)
      false)))

(az/defn load-atlas!
  "Load packed RGBA asset bytes into our own mapped GPU heap."
  :- :void []
  (let [file (stdio/fopen "resources/demo/atlas.rgba" "rb")
        pixels (az/cast mapped-mesh-vertices [:c-pointer :u8])
        offset (* frame-capacity (ak/sizeOf mesh/GpuVertex))]
    (when (ak/== file null) (std-debug/panic "Missing resources/demo/atlas.rgba; run :prepare" []))
    (ak/defer (set! _ (stdio/fclose file)))
    (when (ak/!= (stdio/fread (ak/& (az/index pixels offset)) 1 atlas-bytes file) atlas-bytes)
      (std-debug/panic "Invalid La Professeure RGBA atlas size; run :prepare" []))))

(az/defn recreate-swapchain! :- :bool []
  (let [^{:var vk/VkSurfaceCapabilitiesKHR} capabilities
        (std-mem/zeroes (az/type vk/VkSurfaceCapabilitiesKHR))]
    (check (vk/vkGetPhysicalDeviceSurfaceCapabilitiesKHR physical-device surface (ak/& capabilities)))
    (let [extent (choose-extent capabilities (framebuffer-extent))]
      (when (or (ak/== (az/field extent width) 0) (ak/== (az/field extent height) 0))
        (ak/return false))))
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
    (when (ak/!= old-format swapchain-format)
      (vk/vkDestroyPipeline device mesh-pipeline null)
      (vk/vkDestroyPipelineLayout device mesh-pipeline-layout null)
      (vk/vkDestroyRenderPass device render-pass null)
      (create-render-pass!)
      (create-mesh-pipeline!)))
  (create-framebuffers!)
  (create-commands-and-sync!)
  (set! resize-pending false)
  (set! resize-count (+ resize-count 1))
  true)

(az/defn initialize-renderer!
  "Initialize Vulkan against an existing GLFW window."
  :- :bool
  [[window [:optional [:* vk/GLFWwindow]]]]
  (when (ak/! initialized)
    (set! renderer-window window)
    (initialize-instance!)
    (check (vk/glfwCreateWindowSurface instance window null (ak/& surface)))
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
    (set! initialized true))
  initialized)

(az/defn clear-value
  :- vk/VkClearValue
  [[color Color]]
  (vk/VkClearValue
   {:color
    (vk/VkClearColorValue
     {:float32
      (az/array-init
       [:array 4 :f32]
       [(az/field color r)
        (az/field color g)
        (az/field color b)
        (az/field color a)])})}))

(az/defn record-frame
  :- :void
  [[image-index :u32]
   [build-frame FrameBuilder]]
  (let [command-buffer (az/index command-buffers image-index)
        begin-info
        (vk/VkCommandBufferBeginInfo
         {:sType vk/VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO})
        background
        (clear-value (Color {:r 0.0 :g 0.0 :b 0.0 :a 1.0}))
        depth-clear
        (vk/VkClearValue
         {:depthStencil (vk/VkClearDepthStencilValue {:depth 1.0 :stencil 0})})
        clear-values
        (az/array-init [:array 2 vk/VkClearValue] [background depth-clear])
        render-area
        (vk/VkRect2D
         {:offset (vk/VkOffset2D {:x 0 :y 0})
          :extent swapchain-extent})
        pass-info
        (vk/VkRenderPassBeginInfo
         {:sType vk/VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO
          :renderPass render-pass
          :framebuffer (az/index framebuffers image-index)
          :renderArea render-area
          :clearValueCount 2
          :pClearValues (ak/& (az/index clear-values 0))})]
    (check (vk/vkResetCommandBuffer command-buffer 0))
    (check (vk/vkBeginCommandBuffer command-buffer (ak/& begin-info)))
    (vk/vkCmdBeginRenderPass command-buffer (ak/& pass-info) vk/VK_SUBPASS_CONTENTS_INLINE)
    (let [viewport (vk/VkViewport
                     {:x 0.0 :y 0.0
                      :width (ak/floatFromInt (az/field swapchain-extent width))
                      :height (ak/floatFromInt (az/field swapchain-extent height))
                      :minDepth 0.0 :maxDepth 1.0})]
      (vk/vkCmdSetViewport command-buffer 0 1 (ak/& viewport))
      (vk/vkCmdSetScissor command-buffer 0 1 (ak/& render-area)))
    (set! active-command-buffer command-buffer)
    (set! mesh-vertex-count
          (build-frame
           (az/cast mapped-mesh-vertices [:c-pointer mesh/GpuVertex])
           (ak/as :i32 (ak/intCast (az/field swapchain-extent width)))
           (ak/as :i32 (ak/intCast (az/field swapchain-extent height)))))
    (when (> mesh-vertex-count 0)
      (let [root (RootData {:vertices vertex-address
                            :pixels (+ vertex-address (* frame-capacity (ak/sizeOf mesh/GpuVertex)))
                            :light_x light-x :light_y light-y :lighting lighting :reserved 0.0})]
        (vk/vkCmdBindPipeline command-buffer vk/VK_PIPELINE_BIND_POINT_GRAPHICS
                              mesh-pipeline)
        (vk/vkCmdPushConstants command-buffer mesh-pipeline-layout
          (ak/| vk/VK_SHADER_STAGE_VERTEX_BIT vk/VK_SHADER_STAGE_FRAGMENT_BIT)
          0 (ak/intCast (ak/sizeOf RootData)) (ak/& root))
        (vk/vkCmdDraw command-buffer mesh-vertex-count 1 0 0)))

    (vk/vkCmdEndRenderPass command-buffer)
    (check (vk/vkEndCommandBuffer command-buffer))))

(az/defn render!
  "Ask the application for a triangle frame and present it."
  :- :bool
  [[build-frame FrameBuilder]]
  (std-debug/assert initialized)
  (let [extent (framebuffer-extent)]
    (when (or (ak/== (az/field extent width) 0) (ak/== (az/field extent height) 0))
      (ak/return false))
    (when (or resize-pending
              (ak/!= (az/field extent width) (az/field requested-extent width))
              (ak/!= (az/field extent height) (az/field requested-extent height)))
      (when (ak/! (recreate-swapchain!)) (ak/return false))))
  (let [^{:var true :zig/type :u32} image-index 0
        image-ready (az/index image-available synchronization-slot)]
    (check (vk/vkWaitForFences device 1 (ak/& in-flight) vk/VK_TRUE vk/VK_WHOLE_SIZE))
    (let [acquired (vk/vkAcquireNextImageKHR
                     device swapchain vk/VK_WHOLE_SIZE image-ready null (ak/& image-index))]
      ;; Leave the fence signaled if no submission will happen this frame.
      (when (ak/== acquired vk/VK_ERROR_OUT_OF_DATE_KHR)
        (set! resize-pending true) (ak/return false))
      (if (ak/== acquired vk/VK_SUBOPTIMAL_KHR)
        (set! resize-pending true)
        (check acquired)))
    (do
      (check (vk/vkResetFences device 1 (ak/& in-flight)))
      (record-frame image-index build-frame)
      (let [rendering-done (az/index render-finished image-index)
            ^{:zig/type :u32} wait-stage
            (ak/intCast vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            command-buffer (az/index command-buffers image-index)
            submit-info
            (vk/VkSubmitInfo
             {:sType vk/VK_STRUCTURE_TYPE_SUBMIT_INFO
              :waitSemaphoreCount 1
              :pWaitSemaphores (ak/& image-ready)
              :pWaitDstStageMask (ak/& wait-stage)
              :commandBufferCount 1
              :pCommandBuffers (ak/& command-buffer)
              :signalSemaphoreCount 1
              :pSignalSemaphores (ak/& rendering-done)})
            present-info
            (vk/VkPresentInfoKHR
             {:sType vk/VK_STRUCTURE_TYPE_PRESENT_INFO_KHR
              :waitSemaphoreCount 1
              :pWaitSemaphores (ak/& rendering-done)
              :swapchainCount 1
              :pSwapchains (ak/& swapchain)
              :pImageIndices (ak/& image-index)})]
        (check (vk/vkQueueSubmit graphics-queue 1 (ak/& submit-info) in-flight))
        (let [presented (vk/vkQueuePresentKHR graphics-queue (ak/& present-info))]
          (if (resize-result? presented)
            (set! resize-pending true)
            (check presented)))
        (set! frame-count (+ frame-count 1))
        (set! synchronization-slot (mod (+ synchronization-slot 1) 2)))))
  true)

(az/defn renderer-snapshot
  :- RendererSnapshot
  []
  (RendererSnapshot
   {:initialized initialized
    :frames frame-count
    :width (az/field swapchain-extent width)
    :height (az/field swapchain-extent height)
    :images image-count
    :queue_family queue-family}))

(az/defn renderer-wait-idle!
  :- :void
  []
  (when initialized
    (check (vk/vkDeviceWaitIdle device))))

(az/defn shutdown-renderer!
  "Destroy desktop Vulkan resources in dependency order."
  :- :void
  []
  (when initialized
    (renderer-wait-idle!)
    (vk/vkDestroyPipeline device mesh-pipeline null)
    (vk/vkDestroyPipelineLayout device mesh-pipeline-layout null)
    (vk/vkUnmapMemory device mesh-vertex-memory)
    (vk/vkDestroyBuffer device mesh-vertex-buffer null)
    (vk/vkFreeMemory device mesh-vertex-memory null)
    (destroy-commands-and-sync!)
    (destroy-swapchain-targets!)
    (vk/vkDestroyRenderPass device render-pass null)
    (vk/vkDestroyDevice device null)
    (vk/vkDestroySurfaceKHR instance surface null)
    (vk/vkDestroyInstance instance null)
    (set! initialized false)
    (set! renderer-window null)
    (set! resize-pending false)
    (set! resize-count 0)
    (set! frame-count 0)
    (set! image-count 0)
    (set! instance null)
    (set! surface null)
    (set! physical-device null)
    (set! device null)
    (set! graphics-queue null)
    (set! swapchain null)
    (set! depth-image null)
    (set! depth-memory null)
    (set! depth-view null)
    (set! render-pass null)
    (set! mesh-pipeline null)
    (set! mesh-pipeline-layout null)
    (set! mesh-vertex-buffer null)
    (set! mesh-vertex-memory null)
    (set! mapped-mesh-vertices null)
    (set! mesh-vertex-count 0)
    (set! active-command-buffer null)
    (set! command-pool null)
    (set! image-available
          (std-mem/zeroes (az/type [:array 2 vk/VkSemaphore])))
    (set! render-finished
          (std-mem/zeroes (az/type [:array 8 vk/VkSemaphore])))
    (set! synchronization-slot 0)
    (set! in-flight null)))
