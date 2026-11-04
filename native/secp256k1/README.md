# Android arm64 secp256k1 JNI

The APK uses the Java bindings from the ACINQ 0.16.0 AAR and stages a 16 KB-aligned build of the same JNI source.

- [ACINQ secp256k1-kmp v0.16.0](https://github.com/ACINQ/secp256k1-kmp/tree/e28303147f764e81443b5cb59c760e6628162116) at `e28303147f764e81443b5cb59c760e6628162116`
- [Bitcoin Core secp256k1](https://github.com/bitcoin-core/secp256k1/tree/0cdc758a56360bf58a851fe91085a327ec97685a) submodule at `0cdc758a56360bf58a851fe91085a327ec97685a`
- CMake 3.31.6, Android NDK 30.0.15729638, ABI `arm64-v8a`, API 26
- `libsecp256k1-jni.so` SHA-256 `d09dd4999e9fe4d1c93ba8421138eb4e0c6eff1168f3bc0252d3afeab1e08512`

Check out the ACINQ commit with its submodule and run these commands from the Pocket Realm root. Set `ACINQ_SOURCE` to the checkout and `ANDROID_NDK` to the pinned NDK.

```sh
cmake -S native/secp256k1 -B native/secp256k1/build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
  -DSECP256K1_KMP_SOURCE_DIR="$ACINQ_SOURCE"
cmake --build native/secp256k1/build --target secp256k1-jni
llvm-strip --strip-unneeded native/secp256k1/build/libsecp256k1-jni.so
```

Use `llvm-strip` from the same NDK. Verify the exported JNI methods against the AAR and the ELF load segments with `llvm-readelf -lW` before staging a replacement. The final APK must contain the staged library byte for byte.
