//! What the iOS app calls through cinterop, `BlitzNative` in `iosMain`; the C side of
//! `include/overmail_html_renderer.h`. A mail is a handle: opened, rendered a tile at a time,
//! asked for links, closed -- the same as `jni.rs` does for Android.

use std::ffi::{CStr, CString, c_char};

use crate::Mail;
use crate::worker::run;

#[unsafe(no_mangle)]
pub extern "C" fn blitz_open(html: *const c_char, css_width: i32, viewport_height: i32, scale: f32) -> i64 {
    if html.is_null() {
        return 0;
    }
    let html = unsafe { CStr::from_ptr(html) }.to_string_lossy().into_owned();
    run(move |mails| {
        let mail = Mail::open(&html, css_width as u32, viewport_height as u32, scale);
        mails.insert(mail)
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn blitz_height(handle: i64) -> i32 {
    run(move |mails| mails.get(handle).map(|mail| mail.height as i32)).flatten().unwrap_or(0)
}

/// The rows as RGBA in a buffer of `*out_len` bytes the caller hands back to [blitz_free_buffer];
/// null when they would not render.
#[unsafe(no_mangle)]
pub extern "C" fn blitz_render(handle: i64, top: i32, height: i32, out_len: *mut usize) -> *mut u8 {
    let Some(rgba) = run(move |mails| mails.get(handle).map(|mail| mail.render(top as u32, height as u32))).flatten() else {
        return std::ptr::null_mut();
    };
    let mut rgba = rgba.into_boxed_slice();
    if !out_len.is_null() {
        unsafe { *out_len = rgba.len() };
    }
    let pointer = rgba.as_mut_ptr();
    std::mem::forget(rgba);
    pointer
}

#[unsafe(no_mangle)]
pub extern "C" fn blitz_free_buffer(buffer: *mut u8, len: usize) {
    if !buffer.is_null() {
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(buffer, len)) });
    }
}

/// The `href` at the point, to be handed back to [blitz_free_string]; null where there is none.
#[unsafe(no_mangle)]
pub extern "C" fn blitz_link_at(handle: i64, x: f32, y: f32) -> *mut c_char {
    run(move |mails| mails.get(handle).and_then(|mail| mail.link_at(x, y)))
        .flatten()
        .and_then(|href| CString::new(href).ok())
        .map_or(std::ptr::null_mut(), CString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "C" fn blitz_free_string(string: *mut c_char) {
    if !string.is_null() {
        drop(unsafe { CString::from_raw(string) });
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn blitz_close(handle: i64) {
    run(move |mails| mails.remove(handle));
}
