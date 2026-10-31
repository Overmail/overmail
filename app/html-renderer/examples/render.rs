//! Renders an html file to a png the way the app does, to try the renderer on a desktop:
//! `cargo run --release --example render -- mail.html mail.png [link-x link-y]`

use std::fs::File;
use std::io::BufWriter;
use std::time::Instant;

use overmail_html_renderer::Mail;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let html = std::fs::read_to_string(&args[0]).expect("readable html");
    let output = args.get(1).cloned().unwrap_or_else(|| "mail.png".into());

    let started = Instant::now();
    let mut mail = Mail::open(&html, 640, 1024, 1080.0 / 640.0);
    let height = mail.height.min(3 * 1728);
    // In two tiles, so tiling is tried too.
    let half = height / 2;
    let mut rgba = mail.render(0, half);
    rgba.extend(mail.render(half, height - half));
    println!("{}x{} px of {} in {:?}", mail.width, height, mail.height, started.elapsed());

    if let (Some(x), Some(y)) = (args.get(2), args.get(3)) {
        println!("link at {x},{y}: {:?}", mail.link_at(x.parse().unwrap(), y.parse().unwrap()));
    }

    let mut encoder = png::Encoder::new(BufWriter::new(File::create(&output).unwrap()), mail.width, height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    encoder.write_header().unwrap().write_image_data(&rgba).unwrap();
}
