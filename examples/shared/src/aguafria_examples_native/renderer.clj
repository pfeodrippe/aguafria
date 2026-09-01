(ns aguafria-examples-native.renderer
  "Reusable hot-reloadable Vulkan triangle-stream renderer."
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

(az/defconst OverlayRenderer
  "Optional development callback recorded inside the active Vulkan render pass."
  (az/type
   [:*const
    [:fn {:callconv :.c}
     [{:name command_buffer :type :u64}]
     :void]]))

(az/defconst development-overlays-enabled
  "Standalone roots can disable every trace of the development callback."
  (if (ak/hasDecl (ak/import "root") "aguafria_development_overlays")
    (az/field (ak/import "root") aguafria_development_overlays)
    true))

(az/defstruct RendererSnapshot
  "Inspectable state for the live desktop Vulkan renderer."
  {:layout :extern}
  [[:initialized :bool]
   [:frames :u64]
   [:width :u32]
   [:height :u32]
   [:images :u32]
   [:queue_family :u32]])

(az/defstruct RendererInterop
  "Opaque Vulkan addresses needed by optional native development overlays."
  {:layout :extern}
  [[:valid :bool]
   [:instance :u64]
   [:physical_device :u64]
   [:device :u64]
   [:queue :u64]
   [:queue_family :u32]
   [:render_pass :u64]
   [:image_count :u32]])

(az/defvar initialized false)

(az/defvar frame-count :u64 0)

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

(az/defvar render-finished [:array 2 vk/VkSemaphore]
  (std-mem/zeroes (az/type [:array 2 vk/VkSemaphore])))

(az/defvar in-flight vk/VkFence null)

(az/defvar synchronization-slot :usize 0)

(az/defvar active-command-buffer vk/VkCommandBuffer null)

(az/defvar mesh-pipeline vk/VkPipeline null)

(az/defvar mesh-pipeline-layout vk/VkPipelineLayout null)

(az/defvar mesh-vertex-buffer vk/VkBuffer null)

(az/defvar mesh-vertex-memory vk/VkDeviceMemory null)

(az/defvar mapped-mesh-vertices [:optional [:* :anyopaque]] null)

(az/defvar mesh-vertex-count :u32 0)

(az/defvar overlay-renderer [:optional OverlayRenderer] null)

(az/defvar shader-code [:array 16384 :u32]
  (std-mem/zeroes (az/type [:array 16384 :u32])))

(az/defn check
  "Assert a Vulkan result and keep the result visible in generated Zig."
  :- :void
  [[result vk/VkResult]]
  (std-debug/assert (ak/== result vk/VK_SUCCESS)))

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
            :apiVersion vk/VK_API_VERSION_1_0})
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
        create-info
        (vk/VkDeviceCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
          :queueCreateInfoCount 1
          :pQueueCreateInfos (ak/& queue-info)
          :enabledExtensionCount 2
          :ppEnabledExtensionNames (ak/& (az/index extensions 0))})]
    (check (vk/vkCreateDevice physical-device (ak/& create-info) null (ak/& device)))
    (vk/vkGetDeviceQueue device queue-family 0 (ak/& graphics-queue))))

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
    (set! swapchain-extent (az/field capabilities currentExtent))
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
    ;; Presentation may still be consuming a signaled binary semaphore after
    ;; vkQueuePresentKHR returns. Alternate two pairs while retaining one
    ;; in-flight submission, keeping the single mapped frame buffer race-free.
    (dotimes [slot 2]
      (check (vk/vkCreateSemaphore
              device (ak/& semaphore-info) null
              (ak/& (az/index image-available slot))))
      (check (vk/vkCreateSemaphore
              device (ak/& semaphore-info) null
              (ak/& (az/index render-finished slot)))))
    (check (vk/vkCreateFence device (ak/& fence-info) null (ak/& in-flight)))))

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
                           (* mesh/frame-capacity (ak/sizeOf mesh/GpuVertex)))
        buffer-info
        (vk/VkBufferCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
          :size buffer-size
          :usage vk/VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
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
          allocate-info
          (vk/VkMemoryAllocateInfo
           {:sType vk/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
            :allocationSize (az/field requirements size)
            :memoryTypeIndex memory-type})]
      (std-debug/assert (ak/!= memory-type 0xffffffff))
      (check (vk/vkAllocateMemory device (ak/& allocate-info) null
                                  (ak/& mesh-vertex-memory)))
      (check (vk/vkBindBufferMemory device mesh-vertex-buffer
                                    mesh-vertex-memory 0))
      (check (vk/vkMapMemory device mesh-vertex-memory 0 buffer-size 0
                            (ak/& mapped-mesh-vertices))))))

(az/defn load-shader-module
  "Load one checked-in SPIR-V shader and create its Vulkan module."
  :-
  vk/VkShaderModule
  [[path [:pointer {:size :c :const? true} :u8]]]
  (let [file (stdio/fopen path "rb")
        ^{:var true} module (ak/as vk/VkShaderModule null)]
    (std-debug/assert (ak/!= file null))
    (let [bytes (stdio/fread (ak/& (az/index shader-code 0))
                              1 (* 16384 (ak/sizeOf :u32)) file)]
      (set! _ (stdio/fclose file))
      (std-debug/assert (and (> bytes 0) (ak/== (mod bytes 4) 0)))
      (let [create-info
            (vk/VkShaderModuleCreateInfo
             {:sType vk/VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO
              :codeSize bytes
              :pCode (ak/& (az/index shader-code 0))})]
        (check (vk/vkCreateShaderModule device (ak/& create-info) null
                                        (ak/& module)))))
    module))

(az/defn create-mesh-pipeline!
  "Create the Vulkan triangle pipeline used by every Kenney model."
  :-
  :void
  []
  (let [vertex-module (load-shader-module "resources/shaders/mesh.vert.spv")
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
        binding
        (vk/VkVertexInputBindingDescription
         {:binding 0
          :stride (ak/intCast (ak/sizeOf mesh/GpuVertex))
          :inputRate vk/VK_VERTEX_INPUT_RATE_VERTEX})
        attributes
        (az/array-init
         [:array 2 vk/VkVertexInputAttributeDescription]
         [(vk/VkVertexInputAttributeDescription
           {:location 0 :binding 0 :format vk/VK_FORMAT_R32G32B32_SFLOAT :offset 0})
          (vk/VkVertexInputAttributeDescription
           {:location 1 :binding 0 :format vk/VK_FORMAT_R32G32B32_SFLOAT :offset 12})])
        vertex-input
        (vk/VkPipelineVertexInputStateCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO
          :vertexBindingDescriptionCount 1
          :pVertexBindingDescriptions (ak/& binding)
          :vertexAttributeDescriptionCount 2
          :pVertexAttributeDescriptions (ak/& (az/index attributes 0))})
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
          :depthTestEnable vk/VK_TRUE
          :depthWriteEnable vk/VK_TRUE
          :depthCompareOp vk/VK_COMPARE_OP_LESS
          :depthBoundsTestEnable vk/VK_FALSE
          :stencilTestEnable vk/VK_FALSE
          :minDepthBounds 0.0
          :maxDepthBounds 1.0})
        color-attachment
        (vk/VkPipelineColorBlendAttachmentState
         {:blendEnable vk/VK_FALSE
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
        layout-info
        (vk/VkPipelineLayoutCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO})]
    (check (vk/vkCreatePipelineLayout device (ak/& layout-info) null
                                      (ak/& mesh-pipeline-layout)))
    (let [pipeline-info
          (vk/VkGraphicsPipelineCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO
            :stageCount 2
            :pStages (ak/& (az/index stages 0))
            :pVertexInputState (ak/& vertex-input)
            :pInputAssemblyState (ak/& input-assembly)
            :pViewportState (ak/& viewport-state)
            :pRasterizationState (ak/& rasterization)
            :pMultisampleState (ak/& multisample)
            :pDepthStencilState (ak/& depth-stencil)
            :pColorBlendState (ak/& color-blend)
            :layout mesh-pipeline-layout
            :renderPass render-pass
            :subpass 0})]
      (check (vk/vkCreateGraphicsPipelines device null 1 (ak/& pipeline-info)
                                           null (ak/& mesh-pipeline))))
    (vk/vkDestroyShaderModule device fragment-module null)
    (vk/vkDestroyShaderModule device vertex-module null)))

(az/defn initialize-renderer!
  "Initialize Vulkan against an existing GLFW window."
  :- :bool
  [[window [:optional [:* vk/GLFWwindow]]]]
  (when (ak/! initialized)
    (initialize-instance!)
    (check (vk/glfwCreateWindowSurface instance window null (ak/& surface)))
    (select-device-and-queue!)
    (create-device!)
    (create-swapchain!)
    (create-image-views!)
    (create-depth-resources!)
    (create-render-pass!)
    (create-mesh-buffer!)
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

(az/defn set-overlay-renderer!
  "Install or clear a development-only render-pass callback."
  :-
  :void
  [[callback [:optional OverlayRenderer]]]
  (set! overlay-renderer callback))

(az/defn overlay-installed?
  "Whether a development overlay callback is attached to the render pass."
  :-
  :bool
  []
  (ak/!= overlay-renderer null))

(az/defn renderer-interop
  "Expose opaque renderer handles without giving overlays ownership of them."
  :-
  RendererInterop
  []
  (if initialized
    (RendererInterop
     {:valid true
      :instance (ak/intCast (ak/intFromPtr (az/unwrap instance)))
      :physical_device (ak/intCast (ak/intFromPtr (az/unwrap physical-device)))
      :device (ak/intCast (ak/intFromPtr (az/unwrap device)))
      :queue (ak/intCast (ak/intFromPtr (az/unwrap graphics-queue)))
      :queue_family queue-family
      :render_pass (ak/intCast (ak/intFromPtr (az/unwrap render-pass)))
      :image_count image-count})
    (RendererInterop
     {:valid false :instance 0 :physical_device 0 :device 0 :queue 0
      :queue_family 0 :render_pass 0 :image_count 0})))

(az/defn clear-rect
  :- :void
  [[command-buffer vk/VkCommandBuffer]
   [color Color]
   [x :i32]
   [y :i32]
   [width :i32]
   [height :i32]]
  (when (and (> width 0) (> height 0))
    (let [attachment
          (vk/VkClearAttachment
           {:aspectMask vk/VK_IMAGE_ASPECT_COLOR_BIT
            :colorAttachment 0
            :clearValue (clear-value color)})
          rectangle
          (vk/VkClearRect
           {:rect
            (vk/VkRect2D
             {:offset (vk/VkOffset2D {:x x :y y})
              :extent
              (vk/VkExtent2D
               {:width (ak/as :u32 (ak/intCast width))
                :height (ak/as :u32 (ak/intCast height))})})
            :baseArrayLayer 0
            :layerCount 1})]
      (vk/vkCmdClearAttachments
       command-buffer 1 (ak/& attachment) 1 (ak/& rectangle)))))

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
    (set! active-command-buffer command-buffer)
    (set! mesh-vertex-count
          (build-frame
           (az/cast mapped-mesh-vertices [:c-pointer mesh/GpuVertex])
           (ak/as :i32 (ak/intCast (az/field swapchain-extent width)))
           (ak/as :i32 (ak/intCast (az/field swapchain-extent height)))))
    (when (> mesh-vertex-count 0)
      (let [offset (ak/as vk/VkDeviceSize 0)]
        (vk/vkCmdBindPipeline command-buffer vk/VK_PIPELINE_BIND_POINT_GRAPHICS
                              mesh-pipeline)
        (vk/vkCmdBindVertexBuffers command-buffer 0 1
                                   (ak/& mesh-vertex-buffer) (ak/& offset))
        (vk/vkCmdDraw command-buffer mesh-vertex-count 1 0 0)))
    (when (and development-overlays-enabled
               (ak/!= overlay-renderer null))
      ((az/unwrap overlay-renderer)
       (ak/intCast (ak/intFromPtr (az/unwrap command-buffer)))))
    (vk/vkCmdEndRenderPass command-buffer)
    (check (vk/vkEndCommandBuffer command-buffer))))

(az/defn render!
  "Ask the application for a triangle frame and present it."
  :- :bool
  [[build-frame FrameBuilder]]
  (std-debug/assert initialized)
  (let [^{:var true :zig/type :u32} image-index 0
        image-ready (az/index image-available synchronization-slot)
        rendering-done (az/index render-finished synchronization-slot)]
    (check (vk/vkWaitForFences device 1 (ak/& in-flight) vk/VK_TRUE vk/VK_WHOLE_SIZE))
    (check (vk/vkAcquireNextImageKHR
            device swapchain vk/VK_WHOLE_SIZE image-ready null (ak/& image-index)))
    (do
      (check (vk/vkResetFences device 1 (ak/& in-flight)))
      (record-frame image-index build-frame)
      (let [^{:zig/type :u32} wait-stage
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
        (check (vk/vkQueuePresentKHR graphics-queue (ak/& present-info)))
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
    (set! overlay-renderer null)
    (renderer-wait-idle!)
    (vk/vkDestroyPipeline device mesh-pipeline null)
    (vk/vkDestroyPipelineLayout device mesh-pipeline-layout null)
    (vk/vkUnmapMemory device mesh-vertex-memory)
    (vk/vkDestroyBuffer device mesh-vertex-buffer null)
    (vk/vkFreeMemory device mesh-vertex-memory null)
    (vk/vkDestroyFence device in-flight null)
    (dotimes [slot 2]
      (vk/vkDestroySemaphore device (az/index render-finished slot) null)
      (vk/vkDestroySemaphore device (az/index image-available slot) null))
    (vk/vkDestroyCommandPool device command-pool null)
    (dotimes [index image-count]
      (vk/vkDestroyFramebuffer device (az/index framebuffers index) null)
      (vk/vkDestroyImageView device (az/index image-views index) null))
    (vk/vkDestroyImageView device depth-view null)
    (vk/vkDestroyImage device depth-image null)
    (vk/vkFreeMemory device depth-memory null)
    (vk/vkDestroyRenderPass device render-pass null)
    (vk/vkDestroySwapchainKHR device swapchain null)
    (vk/vkDestroyDevice device null)
    (vk/vkDestroySurfaceKHR instance surface null)
    (vk/vkDestroyInstance instance null)
    (set! initialized false)
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
          (std-mem/zeroes (az/type [:array 2 vk/VkSemaphore])))
    (set! synchronization-slot 0)
    (set! in-flight null)))
