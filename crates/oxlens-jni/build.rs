// author: kodeholic (powered by Claude)
//! build.rs — oxlens-jni 빌드 스크립트
//!
//! Android aarch64 타겟에서 LSE atomics 초기화 C 코드를 컴파일하여 링크.

fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    let target_arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();

    // aarch64 Android 타겟에서만 lse_init.c 컴파일
    if target.contains("android") && target_arch == "aarch64" {
        println!("cargo:warning=Compiling LSE atomics init for Android aarch64");

        cc::Build::new()
            .file("c/lse_init.c")
            .compile("lse_init");
    }

    // 재빌드 트리거: c/ 디렉토리 변경 시
    println!("cargo:rerun-if-changed=c/");
}
