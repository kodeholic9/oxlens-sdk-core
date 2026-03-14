// author: kodeholic (powered by Claude)
//
// Android cdylib getauxval + LSE atomics 초기화
//
// 문제:
// Rust 1.93+ (PR #144938)에서 aarch64-linux-android에 outline-atomics 기본 활성화.
// compiler_builtins가 getauxval() 함수를 .so에 포함시키는데, 이 함수 내부에서
// __getauxval(weak extern)을 호출함. cdylib에서는 이 심볼이 resolve되지 않아
// null function pointer 호출 → SIGSEGV.
//
// 추가로 이 getauxval 심볼이 libc의 진짜 getauxval을 가려서,
// libwebrtc C++ 글로벌 생성자(_GLOBAL__sub_I_webrtc.cpp)가 getauxval을
// 호출하면 .so 내부의 broken stub으로 진입 → 크래시.
//
// 해결:
// 1. __getauxval() 함수를 직접 제공 → compiler_builtins의 getauxval stub 정상화
// 2. __aarch64_have_lse_atomics 변수 초기화 → outline-atomics 런타임 감지 정상화
//
// 이 방식은 constructor 실행 순서와 무관하게 동작 (링크 타임 심볼 resolve).
//
// 참조:
// - rust-lang/rust#144938 (outline-atomics on Android)
// - rust-lang/rust#109064 (outline-atomics violates core policy)
// - compiler_builtins/src/aarch64_linux.rs

#include <dlfcn.h>
#include <stddef.h>
#include <sys/auxv.h>

// HWCAP_ATOMICS: AT_HWCAP 비트 (1 << 8)
#ifndef HWCAP_ATOMICS
#define HWCAP_ATOMICS (1 << 8)
#endif

#ifndef AT_HWCAP
#define AT_HWCAP 16
#endif

// compiler_builtins가 참조하는 전역 변수
_Bool __aarch64_have_lse_atomics __attribute__((weak));

// ================================================================
//  핵심: compiler_builtins의 getauxval()이 호출하는 __getauxval 제공
//
//  compiler_builtins 내부 구조:
//    extern "C" { fn __getauxval(type_: c_ulong) -> c_ulong; }  // weak
//    pub extern "C" fn getauxval(type_: c_ulong) -> c_ulong {
//        __getauxval(type_)   // ← 이게 null이면 SIGSEGV
//    }
//
//  우리가 __getauxval을 제공하면 링크 타임에 resolve → 크래시 해결.
//  Android API 18+에서 getauxval은 항상 사용 가능하므로 직접 호출.
// ================================================================
unsigned long __getauxval(unsigned long type) {
    // Android Bionic의 getauxval을 dlsym으로 찾아 호출.
    // 직접 getauxval() 호출하면 .so 내부 stub으로 재귀하므로 dlsym 필수.
    typedef unsigned long (*getauxval_fn)(unsigned long);
    static getauxval_fn real_fn = NULL;
    static int resolved = 0;

    if (!resolved) {
        // RTLD_NEXT: 현재 .so 다음 라이브러리(libc)에서 검색
        real_fn = (getauxval_fn)dlsym(RTLD_NEXT, "getauxval");
        resolved = 1;
    }

    if (real_fn) {
        return real_fn(type);
    }

    // fallback: getauxval을 못 찾으면 0 반환 (기능 미지원 취급)
    return 0;
}

// ================================================================
//  LSE atomics 플래그 초기화 (constructor)
//  outline-atomics 런타임 감지용 — __getauxval이 정상이면 자동으로 되지만
//  혹시 모를 타이밍 이슈 대비 명시적 초기화.
// ================================================================
__attribute__((constructor(101)))
static void oxlens_init_lse_atomics(void) {
    unsigned long hwcap = __getauxval(AT_HWCAP);
    __aarch64_have_lse_atomics = (hwcap & HWCAP_ATOMICS) != 0;
}
