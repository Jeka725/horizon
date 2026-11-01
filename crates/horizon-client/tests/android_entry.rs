//! Compile-time API contract shared by desktop and Android frontends.

#[test]
fn client_result_type_is_public_to_native_frontends() {
    let result: horizon_client::Result<()> = Ok(());
    assert!(result.is_ok());
}
