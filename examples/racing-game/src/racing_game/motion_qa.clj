(ns racing-game.motion-qa
  "Opt-in frame-thread telemetry. No polling-induced timing jitter; capture
  stops before the JVM reads the buffer. Times are GLFW monotonic seconds."
  (:require [aguafria.keyword :as ak]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aguafria.zig :as az]
            [racing-game.physics :as physics]
            [racing-game.render3d :as camera]
            [racing-game.simulation :as sim]))

(az/defstruct Frame {:layout :extern}
  [[:time :f64] [:tick :u64] [:x :f32] [:y :f32] [:heading :f32]
   [:speed :f32] [:lane :f32] [:progress :f32]
   [:camera_x :f32] [:camera_y :f32] [:camera_yaw :f32]
   [:screen_x :f32] [:screen_y :f32]])

(az/defvar frames [:array 8192 Frame] ak/undefined)

;; Separate buffers preserve Frame's existing live ABI. Every sample is
;; written by the frame thread and protected by the same capture/read lock.
(az/defstruct PoseContext {:layout :extern}
  [[:world :u64] [:racer :u32] [:zoom :f32] [:phase :f32]
   [:camera_z :f32] [:body_screen_x :f32] [:body_screen_y :f32]])

(az/defvar pose-contexts [:array 8192 PoseContext] ak/undefined)

(az/defvar body-frames [:array 8192 [:array 10 physics/BodyState]] ak/undefined)

(az/defvar frame-count :u32 0)

(az/defvar capturing false)

(az/defvar start-time :f64 -1.0)

(az/defvar capture-state :u8 0)

(az/defn start!
  "Request capture on the render thread; false if capturing/exporting already."
  :- :bool []
  (ak/== (ak/cmpxchgStrong :u8 (ak/& capture-state) 0 1 :.acq_rel :.acquire) ak/null))

(az/defn record! :- :void [[now :f64]]
  (when (ak/== (ak/atomicLoad :u8 (ak/& capture-state) :.acquire) 1)
    (set! frame-count 0)
    (set! start-time now)
    (set! capturing true)
    (ak/atomicStore :u8 (ak/& capture-state) 2 :.release))
  (when capturing
    (when (< start-time 0.0) (set! start-time now))
    (if (or (>= frame-count 8192) (>= (- now start-time) 40.0))
      (do (set! capturing false)
          (ak/atomicStore :u8 (ak/& capture-state) 0 :.release))
      (let [r (sim/racer-view camera/camera-racer)
            shown (camera/presentation-view camera/camera-racer)
            screen (camera/project (az/field shown x) (az/field shown y) camera/camera-z)]
        (set! (az/index frames frame-count)
              (Frame {:time now :tick (az/field (sim/snapshot) tick)
                      :x (az/field r x) :y (az/field r y)
                      :heading (az/field r heading) :speed (az/field r speed)
                      :lane (az/field r lane) :progress (az/field r progress)
                      :camera_x camera/camera-x :camera_y camera/camera-y
                      :camera_yaw camera/camera-yaw
                      :screen_x (az/field screen x) :screen_y (az/field screen y)}))
        (let [chassis (camera/presentation-pose camera/camera-racer 0)
              actual-screen (camera/project (* 0.001 (az/field chassis x))
                                            (* 0.001 (az/field chassis y))
                                            (* 0.001 (az/field chassis z)))]
          (set! (az/index pose-contexts frame-count)
                (PoseContext {:world (az/field (sim/snapshot) world_address)
                              :racer camera/camera-racer :zoom camera/camera-zoom
                              :phase camera/presentation-alpha :camera_z camera/camera-z
                              :body_screen_x (az/field actual-screen x)
                              :body_screen_y (az/field actual-screen y)})))
        (dotimes [part 5]
          (set! (az/index (az/index body-frames frame-count) part)
                (sim/vehicle-pose camera/camera-racer (ak/intCast part)))
          (set! (az/index (az/index body-frames frame-count) (+ part 5))
                (camera/presentation-pose camera/camera-racer (ak/intCast part))))
        (set! frame-count (+ frame-count 1))))))

(az/defn ready? :- :bool []
  (ak/== (ak/atomicLoad :u8 (ak/& capture-state) :.acquire) 0))

(az/defn begin-read! :- :bool []
  (ak/== (ak/cmpxchgStrong :u8 (ak/& capture-state) 0 3 :.acq_rel :.acquire) ak/null))

(az/defn end-read! :- :void []
  (ak/atomicStore :u8 (ak/& capture-state) 0 :.release))

(az/defn count-frames :- :u32 [] frame-count)

(az/defn frame-at :- Frame [[index :u32]]
  (az/index frames (ak/min index (- (ak/max frame-count 1) 1))))

(az/defn context-at :- PoseContext [[index :u32]]
  (az/index pose-contexts (ak/min index (- (ak/max frame-count 1) 1))))

(az/defn body-at :- physics/BodyState [[index :u32] [part :u32]]
  (az/index (az/index body-frames (ak/min index (- (ak/max frame-count 1) 1)))
            (ak/min part 9)))

(def body-fields [:x :y :z :vx :vy :vz :qx :qy :qz :qw :wx :wy :wz])

(def body-prefixes
  (for [kind ["raw" "shown"] part (range 5)] (str kind "_" part "_")))

(defn export!
  "Write a completed capture, including all five actual and rendered body
  poses. Raw bodies use metres, radians and seconds; camera positions use km.
  Part 0 is the chassis, parts 1–4 are the wheels. No physics state is changed."
  [path]
  (when-not (begin-read!)
    (throw (ex-info "Wait for the 40-second capture to finish before exporting" {})))
  (try
   (let [fields [:time :tick :x :y :heading :speed :lane :progress
                :camera_x :camera_y :camera_yaw :screen_x :screen_y]
        context-fields [:world :racer :zoom :phase :camera_z :body_screen_x :body_screen_y]
        columns (concat (map name fields) (map name context-fields)
                        (for [prefix body-prefixes field body-fields] (str prefix (name field))))
        n (count-frames)]
    (with-open [writer (io/writer path)]
      (.write writer (str (str/join "," columns) "\n"))
      (dotimes [i n]
        (let [frame (az/value (frame-at i))
              context (az/value (context-at i))
              bodies (mapcat #(let [body (az/value (body-at i %))]
                                (map body body-fields)) (range 10))]
          (.write writer (str (str/join "," (concat (map frame fields)
                                                    (map context context-fields) bodies)) "\n")))))
    {:frames n :path path})
   (finally (end-read!))))
