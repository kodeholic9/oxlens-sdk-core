# oxlens-sdk consumer ProGuard rules
# JNI 콜백 인터페이스 보존 (Rust에서 reflection으로 호출)
-keep interface com.oxlens.sdk.OxLensEventListener { *; }
-keep class com.oxlens.sdk.OxLensClient { *; }
