use std::sync::Mutex;
use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JClass, JString, JByteArray};
use jni::sys::{jboolean, jlong, jbyteArray};
use jni::JNIEnv;

struct EngineWrapper {
    engine: Engine,
}

#[no_mangle]
pub extern "system" fn Java_com_bravemusic_player_adblock_BraveShield_createEngineFromRules(
    mut env: JNIEnv,
    _: JClass,
    rules_text: JString,
) -> jlong {
    let rules_str: String = match env.get_string(&rules_text) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let engine = Engine::new_with_list_text(rules_str);
    let wrapper = Box::new(Mutex::new(EngineWrapper { engine }));
    Box::into_raw(wrapper) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_bravemusic_player_adblock_BraveShield_createEngineFromSerialized(
    env: JNIEnv,
    _: JClass,
    serialized_bytes: JByteArray,
) -> jlong {
    let bytes = match env.convert_byte_array(serialized_bytes) {
        Ok(b) => b,
        Err(_) => return 0,
    };

    let mut engine = Engine::default();
    if engine.deserialize(&bytes).is_err() {
        return 0;
    }

    let wrapper = Box::new(Mutex::new(EngineWrapper { engine }));
    Box::into_raw(wrapper) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_bravemusic_player_adblock_BraveShield_serializeEngine(
    env: JNIEnv,
    _: JClass,
    engine_ptr: jlong,
) -> jbyteArray {
    if engine_ptr == 0 {
        return std::ptr::null_mut();
    }

    let wrapper = unsafe { &*(engine_ptr as *const Mutex<EngineWrapper>) };
    let guard = match wrapper.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };

    let serialized = guard.engine.serialize();
    match env.byte_array_from_slice(&serialized) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bravemusic_player_adblock_BraveShield_shouldBlock(
    mut env: JNIEnv,
    _: JClass,
    engine_ptr: jlong,
    url: JString,
    source_url: JString,
    resource_type: JString,
    method: JString,
) -> jboolean {
    if engine_ptr == 0 {
        return false as jboolean;
    }

    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return false as jboolean,
    };
    let source_str: String = match env.get_string(&source_url) {
        Ok(s) => s.into(),
        Err(_) => return false as jboolean,
    };
    let type_str: String = match env.get_string(&resource_type) {
        Ok(s) => s.into(),
        Err(_) => return false as jboolean,
    };
    let method_str: String = match env.get_string(&method) {
        Ok(s) => s.into(),
        Err(_) => return false as jboolean,
    };

    let wrapper = unsafe { &*(engine_ptr as *const Mutex<EngineWrapper>) };
    let guard = match wrapper.lock() {
        Ok(g) => g,
        Err(_) => return false as jboolean,
    };

    if let Ok(request) = Request::new(&url_str, &source_str, &type_str, &method_str) {
        let result = guard.engine.check_network_request(&request);
        result.should_block() as jboolean
    } else {
        false as jboolean
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bravemusic_player_adblock_BraveShield_getCosmeticResources(
    mut env: JNIEnv,
    _: JClass,
    engine_ptr: jlong,
    url: JString,
) -> jbyteArray {
    if engine_ptr == 0 {
        return std::ptr::null_mut();
    }

    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let wrapper = unsafe { &*(engine_ptr as *const Mutex<EngineWrapper>) };
    let guard = match wrapper.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };

    let resources = guard.engine.url_cosmetic_resources(&url_str);
    let selectors: Vec<&str> = resources.hide_selectors.iter().map(|s| s.as_str()).collect();
    let stylesheet = if selectors.is_empty() {
        String::new()
    } else {
        selectors.join(", ") + " { display: none !important; }"
    };
    
    match env.byte_array_from_slice(stylesheet.as_bytes()) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bravemusic_player_adblock_BraveShield_destroyEngine(
    _: JNIEnv,
    _: JClass,
    engine_ptr: jlong,
) {
    if engine_ptr != 0 {
        let _ = unsafe { Box::from_raw(engine_ptr as *mut Mutex<EngineWrapper>) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_engine_blocks_ad() {
        let rules = "||googleads.g.doubleclick.net^\n||youtube.com/pagead/*\n";
        let engine = Engine::new_with_list_text(rules);
        let req = Request::new(
            "https://googleads.g.doubleclick.net/pagead/ads",
            "https://music.youtube.com",
            "script",
            "GET"
        ).unwrap();
        assert!(engine.check_network_request(&req).should_block());
    }
}
