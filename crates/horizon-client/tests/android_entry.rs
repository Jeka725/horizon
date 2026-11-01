//! Compile-time API contract shared by desktop and Android frontends.

#[test]
fn game_loop_entry_is_public_to_native_frontends() {
    let _: fn() -> horizon_client::Result<()> = horizon_client::game::run;
}
