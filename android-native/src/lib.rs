use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::jstring,
};

/// JNI handshake used by the Android shell to verify that the Rust ARM64
/// shared library is loaded and callable. The game loop is not wired here yet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_horizon_android_MainActivity_nativeEngineStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let message = env.new_string(
        "Rust ARM64 engine bridge loaded. Game renderer integration is the next stage."
    ).expect("create JNI status string");
    message.into_raw()
}
