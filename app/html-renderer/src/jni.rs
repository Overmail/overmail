//! What the app calls, `BlitzHtmlRenderer` in `androidMain`. A mail is a handle: opened, rendered
//! a tile at a time, asked for links, closed.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jbyteArray, jfloat, jint, jlong, jstring};

use crate::Mail;
use crate::worker::run;

/// Lays [html] out; the handle, or 0 when it would not. Its size is asked for with `width` and `height`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_es_jvbabi_overmail_ui_components_blitz_BlitzHtmlRenderer_open<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    html: JString<'local>,
    css_width: jint,
    viewport_height: jint,
    scale: jfloat,
) -> jlong {
    let Ok(html) = env.get_string(&html).map(String::from) else { return 0 };
    run(move |mails| {
        let mail = Mail::open(&html, css_width as u32, viewport_height as u32, scale);
        mails.insert(mail)
    })
    .unwrap_or(0)
}

/// How tall the mail is, in device px; 0 for a handle that is not open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_es_jvbabi_overmail_ui_components_blitz_BlitzHtmlRenderer_height<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    run(move |mails| mails.get(handle).map(|mail| mail.height as jint)).flatten().unwrap_or(0)
}

/// The rows [top] to [top] + [height] as RGBA, as wide as the mail; null when it would not render.
#[unsafe(no_mangle)]
pub extern "system" fn Java_es_jvbabi_overmail_ui_components_blitz_BlitzHtmlRenderer_render<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    top: jint,
    height: jint,
) -> jbyteArray {
    let rgba = run(move |mails| mails.get(handle).map(|mail| mail.render(top as u32, height as u32))).flatten();
    match rgba.and_then(|rgba| env.byte_array_from_slice(&rgba).ok()) {
        Some(array) => array.into_raw(),
        None => std::ptr::null_mut(),
    }
}

/// The `href` of the link at [x], [y] in device px of the mail, or null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_es_jvbabi_overmail_ui_components_blitz_BlitzHtmlRenderer_linkAt<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jfloat,
    y: jfloat,
) -> jstring {
    let href = run(move |mails| mails.get(handle).and_then(|mail| mail.link_at(x, y))).flatten();
    match href.and_then(|href| env.new_string(href).ok()) {
        Some(string) => string.into_raw(),
        None => std::ptr::null_mut(),
    }
}

/// Lets the mail go.
#[unsafe(no_mangle)]
pub extern "system" fn Java_es_jvbabi_overmail_ui_components_blitz_BlitzHtmlRenderer_close<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    run(move |mails| mails.remove(handle));
}
