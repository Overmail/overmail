//! Html mails rendered to pixels with Blitz, without a web view: parsed, styled with Stylo, laid
//! out with Taffy and rasterised on the CPU with vello_cpu. What the app gets back are RGBA tiles
//! it puts into bitmaps, see `BlitzMailView` in the app.
//!
//! A mail is laid out once, as a [Mail], and then rendered in tiles of whatever part is shown, at
//! one scale for every place it is shown in: the app only scales the pixels, so a card and the page
//! that grows out of it show the very same picture, and nothing reflows on the way.
//!
//! Offline on purpose: a mail loads nothing, the only images are the `data:` urls the server
//! writes the embedded ones into. A remote image is left out, the way a mail client asks first.

use std::sync::Arc;

use anyrender::{PaintScene as _, render_to_buffer};
use anyrender_vello_cpu::VelloCpuImageRenderer;
use blitz_dom::{DocumentConfig, Node, util::Color};
use blitz_html::HtmlDocument;
use blitz_paint::paint_scene;
use blitz_traits::net::{Bytes, NetHandler, NetProvider, Request};
use blitz_traits::shell::{ColorScheme, Viewport};
use data_url::DataUrl;
use markup5ever::local_name;
use peniko::Fill;
use peniko::kurbo::Rect;
use taffy::{CoreStyle as _, Overflow};

#[cfg(target_os = "android")]
mod jni;
#[cfg(target_os = "ios")]
mod ffi;
pub mod worker;

/// Answers `data:` urls with what they hold and everything else with nothing.
///
/// Every request is answered, right away: Blitz does not lay a document out while a stylesheet
/// linked in its head is outstanding, so one that is never answered -- a web font from Google, say
/// -- leaves the mail empty. Nothing is a stylesheet without rules, and an image or a font that
/// fails to decode, so a remote one is left out all the same.
struct DataUrlsOnly;

impl NetProvider for DataUrlsOnly {
    fn fetch(&self, _doc_id: usize, request: Request, handler: Box<dyn NetHandler>) {
        let bytes = (request.url.scheme() == "data")
            .then(|| DataUrl::process(request.url.as_str()).ok()?.decode_to_vec().ok())
            .flatten()
            .map(|(bytes, _)| bytes)
            .unwrap_or_default();
        handler.bytes(request.url.to_string(), Bytes::from(bytes));
    }
}

/// What the urls of a mail are resolved against. Without one, Blitz takes a `data:` url, which
/// nothing resolves against -- and panics at the first protocol-relative link (`//fonts.…`).
/// `.invalid` is reserved: it names no host, and nothing is fetched from it anyway.
const BASE_URL: &str = "https://mail.invalid/";

/// Where Blitz and a browser disagree on what mails are made of.
///
/// A height on a table row or cell is the least it is in a browser, and mails rely on that: a row
/// with a height of a line holds a whole newsletter. Blitz takes it as the height, so the rows run
/// into each other.
///
/// A cell given another display inline (`<td style="display: block">`, how templates centre their
/// content column) is wrapped in an anonymous cell by a browser and laid out as usual. Blitz drops
/// it from the row, and the whole mail with it, so it is made a cell again. Only the values that
/// show something are matched, so a cell hidden with `display: none` stays hidden.
const FIXES: &str = "<style>\
tr, td, th { height: auto !important; }\
:is(td, th):is(\
[style*='display:block' i], [style*='display: block' i],\
[style*='display:inline' i], [style*='display: inline' i],\
[style*='display:flex' i], [style*='display: flex' i],\
[style*='display:grid' i], [style*='display: grid' i]\
) { display: table-cell !important; }\
</style>";

/// Styles and lays [document] out until its size settles -- the images come in as messages, taken
/// up by the next pass -- and returns how wide and tall its content is, in css px. What overflows
/// counts: a float or a positioned box hangs out of the root's own box, and would be cut off.
fn settle(document: &mut HtmlDocument) -> (f32, f32) {
    let mut size = (f32::NAN, f32::NAN);
    for _ in 0..6 {
        document.resolve(0.0);
        let root = document.as_ref().root_element();
        let settled = (root.scroll_width().max(right_edge(root, 0.0)), root.scroll_height());
        if settled == size {
            break;
        }
        size = settled;
    }
    size
}

/// The right edge of the widest box in [node], in css px from the left of the document, when
/// [node] starts at [left].
///
/// The root's scroll width misses what hangs out of a table cell: a table `width="100%"` with a
/// 775 px table in it is as wide as the viewport for Blitz -- a browser grows it to its content --
/// and the inner one runs out of it uncounted. A box that clips its content (`overflow: hidden`,
/// the hidden preview line of a newsletter) counts with its own width only.
fn right_edge(node: &Node, left: f32) -> f32 {
    let layout = node.final_layout();
    let left = left + layout.location.x;
    let own = left + layout.size.width;
    let clips = node.layout_style().overflow().x != Overflow::Visible;
    if clips {
        return own;
    }
    let children = node.layout_children.borrow();
    // Out of the flow a box is placed against its containing block, and listed with it instead.
    let in_flow = children.iter().flatten().map(|&id| node.with(id))
        .filter(|child| child.oof_containing_block.get().is_none());
    let hoisted = node.hoisted_children.borrow();
    let out_of_flow = hoisted.iter().map(|&id| node.with(id));
    in_flow.chain(out_of_flow).fold(own, |right, child| right.max(right_edge(child, left)))
}

/// A mail laid out, ready to be rendered a tile at a time.
pub struct Mail {
    document: HtmlDocument,
    /// Device px per css px.
    scale: f32,
    /// How wide it is rendered, in device px.
    pub width: u32,
    /// How tall it is, in device px: the mail, or the viewport where the mail is shorter.
    pub height: u32,
}

impl Mail {
    /// [html] laid out [css_width] css px wide in a viewport [viewport_height] css px tall, to be
    /// rendered at [scale] device px per css px.
    pub fn open(html: &str, css_width: u32, viewport_height: u32, scale: f32) -> Mail {
        let width = (css_width as f32 * scale).round() as u32;
        let mut document = HtmlDocument::from_html(
            &format!("{FIXES}{html}"),
            DocumentConfig {
                base_url: Some(BASE_URL.into()),
                net_provider: Some(Arc::new(DataUrlsOnly) as _),
                viewport: Some(Viewport::new(
                    width,
                    (viewport_height as f32 * scale).round() as u32,
                    scale,
                    ColorScheme::Light,
                )),
                ..Default::default()
            },
        );

        let (content_width, mut content_height) = settle(&mut document);

        // Wider than it was laid out for -- a table of a fixed width, a picture -- it is laid out
        // again as wide as it is and zoomed out into the same px, the way a browser shows a page
        // that does not fit: rather than cut off on the right.
        let mut scale = scale;
        if content_width > css_width as f32 + 0.5 {
            let wider = content_width.ceil();
            scale = width as f32 / wider;
            let viewport_height = viewport_height as f32 * wider / css_width as f32;
            document.as_mut().set_viewport(Viewport::new(
                width,
                (viewport_height * scale).round() as u32,
                scale,
                ColorScheme::Light,
            ));
            content_height = settle(&mut document).1;
        }

        let height = (content_height * scale).ceil().max(1.0) as u32;
        Mail { document, scale, width, height }
    }

    /// The rows [top] to [top] + [height] of it, in device px, as RGBA on white.
    pub fn render(&mut self, top: u32, height: u32) -> Vec<u8> {
        let width = self.width;
        let scale = self.scale;
        // Scrolled to the tile: what lies outside it is not even visited.
        self.document
            .as_mut()
            .set_viewport_scroll(blitz_dom::Point { x: 0.0, y: top as f64 / scale as f64 });
        let document = &mut self.document;
        render_to_buffer::<VelloCpuImageRenderer, _>(
            |scene| {
                scene.fill(
                    Fill::NonZero,
                    Default::default(),
                    Color::WHITE,
                    Default::default(),
                    &Rect::new(0.0, 0.0, width as f64, height as f64),
                );
                paint_scene(scene, document.as_mut(), scale as f64, width, height, 0, 0);
            },
            width,
            height,
        )
    }

    /// The `href` of the link at [x], [y] in device px of the whole mail, if there is one.
    pub fn link_at(&mut self, x: f32, y: f32) -> Option<String> {
        let document = self.document.as_mut();
        document.set_viewport_scroll(blitz_dom::Point { x: 0.0, y: 0.0 });
        let hit = document.hit(x / self.scale, y / self.scale)?;
        let mut node = document.get_node(hit.node_id);
        while let Some(current) = node {
            if current.data.is_element_with_tag_name(&local_name!("a")) {
                if let Some(href) = current.attr(local_name!("href")) {
                    return Some(href.to_string());
                }
            }
            node = current.parent.and_then(|parent| document.get_node(parent));
        }
        None
    }
}
