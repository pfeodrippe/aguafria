(ns simple-game.vulkan
  "Small hot-reloadable Vulkan renderer used by the desktop game host."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.desktop-bindings]
            [simple-game.bindings.glfw :as vk]
            [simple-game.game :as game]
            [simple-game.scene :as scene]))

(az/defconst Color
  {:attrs #{:public}}
  scene/Color)

(az/defstruct RendererSnapshot
  "Inspectable state for the live desktop Vulkan renderer."
  {:layout :extern}
  [[:initialized :bool]
   [:frames :u64]
   [:width :u32]
   [:height :u32]
   [:images :u32]
   [:queue_family :u32]])

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

(az/defvar render-pass vk/VkRenderPass null)

(az/defvar framebuffers [:array 8 vk/VkFramebuffer]
  (std-mem/zeroes (az/type [:array 8 vk/VkFramebuffer])))

(az/defvar command-pool vk/VkCommandPool null)

(az/defvar command-buffers [:array 8 vk/VkCommandBuffer]
  (std-mem/zeroes (az/type [:array 8 vk/VkCommandBuffer])))

(az/defvar image-available vk/VkSemaphore null)

(az/defvar render-finished vk/VkSemaphore null)

(az/defvar in-flight vk/VkFence null)

(az/defvar active-command-buffer vk/VkCommandBuffer null)

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
            :pApplicationName "Aguafria simple-game"
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
  (let [attachment
        (vk/VkAttachmentDescription
         {:format swapchain-format
          :samples vk/VK_SAMPLE_COUNT_1_BIT
          :loadOp vk/VK_ATTACHMENT_LOAD_OP_CLEAR
          :storeOp vk/VK_ATTACHMENT_STORE_OP_STORE
          :stencilLoadOp vk/VK_ATTACHMENT_LOAD_OP_DONT_CARE
          :stencilStoreOp vk/VK_ATTACHMENT_STORE_OP_DONT_CARE
          :initialLayout vk/VK_IMAGE_LAYOUT_UNDEFINED
          :finalLayout vk/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR})
        attachment-reference
        (vk/VkAttachmentReference
         {:attachment 0
          :layout vk/VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL})
        subpass
        (vk/VkSubpassDescription
         {:pipelineBindPoint vk/VK_PIPELINE_BIND_POINT_GRAPHICS
          :colorAttachmentCount 1
          :pColorAttachments (ak/& attachment-reference)})
        dependency
        (vk/VkSubpassDependency
         {:srcSubpass vk/VK_SUBPASS_EXTERNAL
          :dstSubpass 0
          :srcStageMask vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
          :dstStageMask vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
          :dstAccessMask vk/VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT})
        create-info
        (vk/VkRenderPassCreateInfo
         {:sType vk/VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO
          :attachmentCount 1
          :pAttachments (ak/& attachment)
          :subpassCount 1
          :pSubpasses (ak/& subpass)
          :dependencyCount 1
          :pDependencies (ak/& dependency)})]
    (check (vk/vkCreateRenderPass device (ak/& create-info) null (ak/& render-pass)))))

(az/defn create-framebuffers!
  :- :void
  []
  (dotimes [index image-count]
    (let [attachment (az/index image-views index)
          create-info
          (vk/VkFramebufferCreateInfo
           {:sType vk/VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO
            :renderPass render-pass
            :attachmentCount 1
            :pAttachments (ak/& attachment)
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
    (check (vk/vkCreateSemaphore
            device (ak/& semaphore-info) null (ak/& image-available)))
    (check (vk/vkCreateSemaphore
            device (ak/& semaphore-info) null (ak/& render-finished)))
    (check (vk/vkCreateFence device (ak/& fence-info) null (ak/& in-flight)))))

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
    (create-render-pass!)
    (create-framebuffers!)
    (create-commands-and-sync!)
    (set! initialized true))
  initialized)

(az/defn clear-value
  {:attrs #{:public :implicit-return}}
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

(az/defn clear-rect
  {:attrs #{:public}}
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

(az/defn backend-clear-rect
  "Vulkan implementation of the shared scene's rectangle operation."
  {:attrs #{:public}}
  :-
  :void
  [[color scene/Color]
   [x :i32]
   [y :i32]
   [width :i32]
   [height :i32]]
  (clear-rect active-command-buffer color x y width height))

(az/defn record-frame
  :- :void
  [[image-index :u32]
   [packet game/RenderPacket]]
  (let [command-buffer (az/index command-buffers image-index)
        begin-info
        (vk/VkCommandBufferBeginInfo
         {:sType vk/VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO})
        background
        (clear-value (Color {:r 0.025 :g 0.032 :b 0.055 :a 1.0}))
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
          :clearValueCount 1
          :pClearValues (ak/& background)})]
    (check (vk/vkResetCommandBuffer command-buffer 0))
    (check (vk/vkBeginCommandBuffer command-buffer (ak/& begin-info)))
    (vk/vkCmdBeginRenderPass command-buffer (ak/& pass-info) vk/VK_SUBPASS_CONTENTS_INLINE)
    (set! active-command-buffer command-buffer)
    (scene/draw-frame (ak/& backend-clear-rect)
                      packet
                      (ak/as :i32 (ak/intCast (az/field swapchain-extent width)))
                      (ak/as :i32 (ak/intCast (az/field swapchain-extent height))))
    (vk/vkCmdEndRenderPass command-buffer)
    (check (vk/vkEndCommandBuffer command-buffer))))

(az/defn render!
  "Render one game packet and present it."
  :- :bool
  [[packet game/RenderPacket]]
  (let [^{:var true :zig/type :u32} image-index 0]
    (check (vk/vkWaitForFences device 1 (ak/& in-flight) vk/VK_TRUE vk/VK_WHOLE_SIZE))
    (check (vk/vkAcquireNextImageKHR
            device swapchain vk/VK_WHOLE_SIZE image-available null (ak/& image-index)))
    (check (vk/vkResetFences device 1 (ak/& in-flight)))
    (record-frame image-index packet)
    (let [^{:zig/type :u32} wait-stage
          (ak/intCast vk/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
          command-buffer (az/index command-buffers image-index)
          submit-info
          (vk/VkSubmitInfo
           {:sType vk/VK_STRUCTURE_TYPE_SUBMIT_INFO
            :waitSemaphoreCount 1
            :pWaitSemaphores (ak/& image-available)
            :pWaitDstStageMask (ak/& wait-stage)
            :commandBufferCount 1
            :pCommandBuffers (ak/& command-buffer)
            :signalSemaphoreCount 1
            :pSignalSemaphores (ak/& render-finished)})
          present-info
          (vk/VkPresentInfoKHR
           {:sType vk/VK_STRUCTURE_TYPE_PRESENT_INFO_KHR
            :waitSemaphoreCount 1
            :pWaitSemaphores (ak/& render-finished)
            :swapchainCount 1
            :pSwapchains (ak/& swapchain)
            :pImageIndices (ak/& image-index)})]
      (check (vk/vkQueueSubmit graphics-queue 1 (ak/& submit-info) in-flight))
      (check (vk/vkQueuePresentKHR graphics-queue (ak/& present-info)))
      (set! frame-count (+ frame-count 1))))
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
    (vk/vkDestroyFence device in-flight null)
    (vk/vkDestroySemaphore device render-finished null)
    (vk/vkDestroySemaphore device image-available null)
    (vk/vkDestroyCommandPool device command-pool null)
    (dotimes [index image-count]
      (vk/vkDestroyFramebuffer device (az/index framebuffers index) null)
      (vk/vkDestroyImageView device (az/index image-views index) null))
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
    (set! render-pass null)
    (set! active-command-buffer null)
    (set! command-pool null)
    (set! image-available null)
    (set! render-finished null)
    (set! in-flight null)))
