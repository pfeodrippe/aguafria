(ns aguafria.zig.toolchain-test
  (:require [aguafria.zig.toolchain :as toolchain]
            [aguafria.zig.toolchain-crypto :as crypto]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util Arrays HexFormat]))

(defn- encrypted [plaintext]
  (let [output (ByteArrayOutputStream.)]
    (crypto/encrypt! (ByteArrayInputStream. plaintext) output)
    (.toByteArray output)))

(defn- decrypted [ciphertext]
  (let [output (ByteArrayOutputStream.)]
    (crypto/decrypt! (ByteArrayInputStream. ciphertext) output)
    (.toByteArray output)))

(defn- failure-phase [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error
                 (:aguafria/phase (ex-data error)))))

(defn- with-directory [f]
  (let [directory (.toFile (Files/createTempDirectory "aguafria-toolchain-test-"
                                                     (make-array FileAttribute 0)))]
    (try (f directory)
         (finally
           (doseq [file (reverse (file-seq directory))]
             (Files/deleteIfExists (.toPath file)))))))

(deftest encrypted-archives-round-trip-with-fresh-nonces
  (doseq [plaintext [(byte-array 0)
                     (byte-array (map unchecked-byte (range 200000)))]]
    (let [first-archive (encrypted plaintext)
          second-archive (encrypted plaintext)]
      (is (Arrays/equals plaintext (decrypted first-archive)))
      (is (Arrays/equals plaintext (decrypted second-archive)))
      (is (not (Arrays/equals first-archive second-archive))
          "Repackaging the same archive must use a new nonce"))))

(deftest wrong-keys-corruption-and-truncation-are-rejected
  (let [ciphertext (encrypted (.getBytes "Aguafria toolchain test" "UTF-8"))]
    (testing "a different key cannot authenticate the archive"
      (with-redefs-fn {#'crypto/embedded-key "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}
        #(is (= :embedded-zig-decrypt
                (failure-phase (fn [] (decrypted ciphertext)))))))
    (testing "altering the header, nonce, ciphertext, or tag fails"
      (doseq [offset [0 8 20 (dec (alength ciphertext))]]
        (let [changed (aclone ciphertext)]
          (aset-byte changed offset (unchecked-byte (bit-xor 1 (aget changed offset))))
          (is (= :embedded-zig-decrypt
                 (failure-phase #(decrypted changed)))))))
    (testing "incomplete headers and authentication tags fail"
      (doseq [length [0 7 8 19 20 (dec (alength ciphertext))]]
        (is (= :embedded-zig-decrypt
               (failure-phase #(decrypted (Arrays/copyOf ciphertext (int length))))))))))

(deftest loader-verifies-original-archive-and-removes-failed-output
  (with-directory
    (fn [directory]
      (let [plaintext (.getBytes "original pinned archive" "UTF-8")
            encrypted-file (io/file directory "archive.enc")
            output (io/file directory "decoded.tar.xz")
            manifest {:schema-version 2
                      :archive-resource crypto/archive-resource
                      :archive-encryption crypto/algorithm
                      :archive-size (alength plaintext)
                      :archive-sha256 (.formatHex (HexFormat/of)
                                                  (.digest (MessageDigest/getInstance "SHA-256")
                                                           plaintext))}]
        (with-open [stream (io/output-stream encrypted-file)]
          (.write stream (encrypted plaintext)))
        (with-redefs [io/resource (fn [_] (.toURL (.toURI encrypted-file)))]
          (is (= output (#'toolchain/copy-verified-archive! manifest output)))
          (is (Arrays/equals plaintext (Files/readAllBytes (.toPath output)))))
        (testing "valid encryption does not bypass the upstream checksum or size"
          (with-redefs [io/resource (fn [_] (.toURL (.toURI encrypted-file)))]
            (doseq [invalid [(assoc manifest :archive-sha256 "wrong")
                             (update manifest :archive-size inc)]]
              (is (= :embedded-zig-integrity
                     (failure-phase #(#'toolchain/copy-verified-archive! invalid output))))
              (is (not (.exists output))))))
        (testing "unknown encryption is not silently treated as plaintext"
          (with-redefs [io/resource (fn [_] (.toURL (.toURI encrypted-file)))]
            (is (= :embedded-zig-manifest
                   (failure-phase #(#'toolchain/copy-verified-archive!
                                    (assoc manifest :archive-encryption :unknown) output))))
            (is (not (.exists output)))))
        (testing "tampering fails before extraction and removes the temporary archive"
          (let [changed (Files/readAllBytes (.toPath encrypted-file))]
            (aset-byte changed (dec (alength changed)) (unchecked-byte (bit-xor 1 (last changed))))
            (with-open [stream (io/output-stream encrypted-file)] (.write stream changed)))
          (with-redefs [io/resource (fn [_] (.toURL (.toURI encrypted-file)))]
            (is (= :embedded-zig-decrypt
                   (failure-phase #(#'toolchain/copy-verified-archive! manifest output))))
            (is (not (.exists output)))))
        (testing "plaintext, missing encryption metadata, and split archives are rejected"
          (with-open [stream (io/output-stream encrypted-file)] (.write stream plaintext))
          (with-redefs [io/resource (fn [_] (.toURL (.toURI encrypted-file)))]
            (doseq [invalid [(dissoc manifest :archive-encryption)
                             (dissoc manifest :archive-resource)
                             (assoc manifest :schema-version 1)
                             (assoc manifest :archive-resource "aguafria/toolchain/zig.tar.xz")
                             (assoc manifest :archive-resources ["first" "second"])]]
              (is (= :embedded-zig-manifest
                     (failure-phase #(#'toolchain/copy-verified-archive! invalid output))))
              (is (not (.exists output))))
            (is (= :embedded-zig-decrypt
                   (failure-phase #(#'toolchain/copy-verified-archive! manifest output))))
            (is (not (.exists output)))))
        (testing "a missing encrypted resource does not use the original archive or download"
          (let [requested (atom [])]
            (with-redefs [io/resource (fn [name] (swap! requested conj name) nil)]
              (is (= :embedded-zig-missing
                     (failure-phase #(#'toolchain/copy-verified-archive! manifest output))))
              (is (= [crypto/archive-resource] @requested))
              (is (not (.exists output))))))))))
