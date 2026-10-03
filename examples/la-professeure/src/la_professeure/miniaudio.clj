(ns la-professeure.miniaudio
  "Miniaudio's native API shared by the game and recording studio."
  (:require [aguafria.c :as ac]
            [clojure.java.io :as io]
            [la-professeure.build :as build]))

(build/load-native!)

(ac/defbindings c-api
  (ac/import! "la_professeure_miniaudio"
              (io/file (build/root) "build/vendor/miniaudio/miniaudio.h")
              {:cache-dir (str (io/file (build/root) ".aguafria/c-bindings"))
               :args ["-lc"]})
  [ma_engine ma_sound ma_decoder ma_data_source MA_SUCCESS
   ma_engine_init ma_engine_uninit ma_sound_init_from_data_source
   ma_sound_set_looping ma_sound_set_volume ma_sound_start ma_sound_uninit ma_sound_at_end
   ma_sound_get_cursor_in_pcm_frames ma_sound_seek_to_pcm_frame
   ma_sound_stop ma_sound_is_playing
   ma_decoder_init_file ma_decoder_uninit])
