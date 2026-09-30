(ns aguafria.zig.toolchain-crypto
  "Authenticated encryption for the packaged Zig archive.

  The key deliberately ships in source: this obscures the resource from ordinary
  archive tools, but is not access control against someone who has the JAR."
  (:require [clojure.java.io :as io])
  (:import [java.io DataInputStream EOFException InputStream OutputStream]
           [java.security GeneralSecurityException SecureRandom]
           [java.util Arrays Base64]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def algorithm :aes-256-gcm-v1)
(def archive-resource "aguafria/toolchain/zig.tar.xz.enc")

;; This key is part of the v1 format. Keep it stable to read existing bundles;
;; changing it requires a new format version. It is intentionally not a secret.
(def ^:private embedded-key "Z4HEBfmO69HhVMeue57WvYMCqmgfFqsf3l8ILk82dcQ=")
(def ^:private magic (.getBytes "AGUAZIG1" java.nio.charset.StandardCharsets/US_ASCII))
(def ^:private nonce-size 12)
(def ^:private random (SecureRandom.))

(defn- cipher
  ^Cipher [mode nonce]
  (doto (Cipher/getInstance "AES/GCM/NoPadding")
    (.init (int mode)
           (SecretKeySpec. (.decode (Base64/getDecoder) ^String embedded-key) "AES")
           (GCMParameterSpec. 128 ^bytes nonce))
    (.updateAAD ^bytes magic)))

(defn- transform!
  [^Cipher cipher ^InputStream input ^OutputStream output]
  (let [buffer (byte-array 65536)]
    (loop []
      (let [size (.read input buffer)]
        (when-not (= -1 size)
          (when-let [chunk (.update cipher buffer 0 size)]
            (.write output ^bytes chunk))
          (recur))))
    ;; Call doFinal explicitly so authentication failures can never be ignored.
    (.write output ^bytes (.doFinal cipher))))

(defn encrypt!
  "Encrypt input into output with a fresh nonce and an authenticated format header.
  Accepts io/input-stream and io/output-stream sources; closes both streams."
  [input output]
  (let [nonce (byte-array nonce-size)]
    (.nextBytes random nonce)
    (with-open [input (io/input-stream input)
                output (io/output-stream output)]
      (.write output ^bytes magic)
      (.write output nonce)
      (transform! (cipher Cipher/ENCRYPT_MODE nonce) input output))))

(defn decrypt!
  "Decrypt input into output, rejecting malformed or unauthenticated ciphertext.
  Closes both streams. Callers must discard output on failure and must not use
  plaintext until this function returns successfully."
  [input output]
  (try
    (with-open [input (DataInputStream. (io/input-stream input))
                output (io/output-stream output)]
      (let [header (byte-array (alength ^bytes magic))
            nonce (byte-array nonce-size)]
        (.readFully input header)
        (when-not (Arrays/equals ^bytes magic header)
          (throw (ex-info "Unrecognized encrypted Zig archive format"
                          {:aguafria/phase :embedded-zig-decrypt})))
        (.readFully input nonce)
        (transform! (cipher Cipher/DECRYPT_MODE nonce) input output)))
    (catch GeneralSecurityException cause
      (throw (ex-info "The encrypted Zig archive failed authentication"
                      {:aguafria/phase :embedded-zig-decrypt} cause)))
    (catch EOFException cause
      (throw (ex-info "The encrypted Zig archive is truncated"
                      {:aguafria/phase :embedded-zig-decrypt} cause)))))
