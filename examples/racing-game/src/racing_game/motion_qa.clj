(ns racing-game.motion-qa
  "Opt-in frame-thread telemetry. No polling-induced timing jitter; capture\n  stops before the JVM reads the buffer. Times are GLFW monotonic seconds."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as ak :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aguafria.zig :as a]
            [racing-game.physics :as physics]
            [racing-game.render3d :as camera]
            [racing-game.simulation :as sim]
            [clojure.core :as c]))

(a/defstruct Frame {:layout :extern}
             [[:time :f64] [:tick :u64] [:x :f32] [:y :f32] [:heading :f32]
              [:speed :f32] [:lane :f32] [:progress :f32]
              [:camera_x :f32] [:camera_y :f32] [:camera_yaw :f32]
              [:screen_x :f32] [:screen_y :f32]])

(a/defvar frames [:array 8192 Frame] undefined)

;; Separate buffers preserve Frame's existing live ABI. Every sample is
;; written by the frame thread and protected by the same capture/read lock.
(a/defstruct PoseContext {:layout :extern}
             [[:world :u64] [:racer :u32] [:zoom :f32] [:phase :f32]
              [:camera_z :f32] [:body_screen_x :f32] [:body_screen_y :f32]])

(a/defvar pose-contexts [:array 8192 PoseContext] undefined)

(a/defvar body-frames [:array 8192 [:array 10 physics/BodyState]] undefined)

(a/defvar frame-count :u32 0)

(a/defvar capturing false)

(a/defvar start-time :f64 -1.0)

(a/defvar capture-state :u8 0)

(a/defn start! :bool
  "Request capture on the render thread; false if capturing/exporting already." []
  (== (cmpxchgStrong :u8 (ak/& capture-state) 0 1 :acq_rel :acquire) null))

(a/defn record! :void [[now :f64]]
  (when (== (atomicLoad :u8 (ak/& capture-state) :acquire) 1)
    (= frame-count 0)
    (= start-time now)
    (= capturing true)
    (atomicStore :u8 (ak/& capture-state) 2 :release))
  (when capturing
    (when (< start-time 0.0) (= start-time now))
    (if (or (>= frame-count 8192) (>= (- now start-time) 40.0))
      (do (= capturing false)
          (atomicStore :u8 (ak/& capture-state) 0 :release))
      (let [r (sim/racer-view camera/camera-racer)
            shown (camera/presentation-view camera/camera-racer)
            screen (camera/project (a/field shown x) (a/field shown y) camera/camera-z)]
        (= (a/index frames frame-count)
           (Frame {:time now :tick (a/field (sim/snapshot) tick)
                   :x (a/field r x) :y (a/field r y)
                   :heading (a/field r heading) :speed (a/field r speed)
                   :lane (a/field r lane) :progress (a/field r progress)
                   :camera_x camera/camera-x :camera_y camera/camera-y
                   :camera_yaw camera/camera-yaw
                   :screen_x (a/field screen x) :screen_y (a/field screen y)}))
        (let [chassis (camera/presentation-pose camera/camera-racer 0)
              actual-screen (camera/project (* 0.001 (a/field chassis x))
                                            (* 0.001 (a/field chassis y))
                                            (* 0.001 (a/field chassis z)))]
          (= (a/index pose-contexts frame-count)
             (PoseContext {:world (a/field (sim/snapshot) world_address)
                           :racer camera/camera-racer :zoom camera/camera-zoom
                           :phase camera/presentation-alpha :camera_z camera/camera-z
                           :body_screen_x (a/field actual-screen x)
                           :body_screen_y (a/field actual-screen y)})))
        (dotimes [part 5]
          (= (a/index (a/index body-frames frame-count) part)
             (sim/vehicle-pose camera/camera-racer (intCast part)))
          (= (a/index (a/index body-frames frame-count) (+ part 5))
             (camera/presentation-pose camera/camera-racer (intCast part))))
        (= frame-count (+ frame-count 1))))))

(a/defn ready? :bool []
  (== (atomicLoad :u8 (ak/& capture-state) :acquire) 0))

(a/defn begin-read! :bool []
  (== (cmpxchgStrong :u8 (ak/& capture-state) 0 3 :acq_rel :acquire) null))

(a/defn end-read! :void []
  (atomicStore :u8 (ak/& capture-state) 0 :release))

(a/defn count-frames :u32 [] frame-count)

(a/defn frame-at Frame [[index :u32]]
  (a/index frames (min index (- (max frame-count 1) 1))))

(a/defn context-at PoseContext [[index :u32]]
  (a/index pose-contexts (min index (- (max frame-count 1) 1))))

(a/defn body-at physics/BodyState [[index :u32] [part :u32]]
  (a/index (a/index body-frames (min index (- (max frame-count 1) 1)))
           (min part 9)))

(def body-fields [:x :y :z :vx :vy :vz :qx :qy :qz :qw :wx :wy :wz])

(def body-prefixes
  (c/for [kind ["raw" "shown"] part (c/range 5)] (c/str kind "_" part "_")))

(c/defn export!
  "Write a completed capture, including all five actual and rendered body
  poses. Raw bodies use metres, radians and seconds; camera positions use km.
  Part 0 is the chassis, parts 1–4 are the wheels. No physics state is changed."
  [path]
  (when-not (begin-read!)
    (throw (c/ex-info "Wait for the 40-second capture to finish before exporting" {})))
  (try
    (let [fields [:time :tick :x :y :heading :speed :lane :progress
                  :camera_x :camera_y :camera_yaw :screen_x :screen_y]
          context-fields [:world :racer :zoom :phase :camera_z :body_screen_x :body_screen_y]
          columns (c/concat (c/map c/name fields) (c/map c/name context-fields)
                            (c/for [prefix body-prefixes field body-fields] (c/str prefix (c/name field))))
          n (count-frames)]
      (c/with-open [writer (io/writer path)]
        (.write writer (c/str (str/join "," columns) "\n"))
        (dotimes [i n]
          (let [frame (a/value (frame-at i))
                context (a/value (context-at i))
                bodies (c/mapcat #(let [body (a/value (body-at i %))]
                                    (c/map body body-fields)) (c/range 10))]
            (.write writer (c/str (str/join "," (c/concat (c/map frame fields)
                                                          (c/map context context-fields) bodies)) "\n")))))
      {:frames n :path path})
    (finally (end-read!))))
