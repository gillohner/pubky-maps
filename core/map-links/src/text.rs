//! Names, queries and shared text.

use percent_encoding::percent_decode_str;

use crate::{MAX_NAME_CHARS, MapLink, parse};

/// A name or query as shown: control characters and runs of whitespace become one space,
/// trimmed, at most [`MAX_NAME_CHARS`] characters. `None` when nothing is left.
pub(crate) fn clean(s: &str) -> Option<String> {
    let mut out = String::new();
    let mut count = 0;
    for word in s.split(|c: char| c.is_whitespace() || c.is_control()) {
        if word.is_empty() {
            continue;
        }
        if !out.is_empty() {
            if count + 1 >= MAX_NAME_CHARS {
                break;
            }
            out.push(' ');
            count += 1;
        }
        for c in word.chars() {
            if count == MAX_NAME_CHARS {
                break;
            }
            out.push(c);
            count += 1;
        }
    }
    (!out.is_empty()).then_some(out)
}

/// A percent-encoded path segment as text; `+` is a space (Google and others write names so).
pub(crate) fn decode_segment(s: &str) -> String {
    percent_decode_str(&s.replace('+', " "))
        .decode_utf8_lossy()
        .into_owned()
}

/// The first map link in shared text, such as "Café Sprüngli\nhttps://maps.app.goo.gl/…" from a
/// share sheet. When the link carries no name, the first line above it is taken as the name,
/// since map apps put the place name there.
pub fn find_in_text(text: &str) -> Option<MapLink> {
    if text.len() > crate::MAX_LINK_LEN * 2 {
        return None;
    }
    if let Some(link) = parse(text) {
        return Some(link);
    }
    for (start, token) in tokens(text) {
        let token = token.trim_end_matches(['.', ',', ';', ')', '>', '"', '\'']);
        let token = token.trim_start_matches(['(', '<', '"', '\'']);
        let Some(mut link) = parse(token) else {
            continue;
        };
        if link.name.is_none() {
            // Only whole lines above the link: text on the link's own line is a sentence.
            let before = text[..start]
                .rsplit_once('\n')
                .map_or("", |(lines, _)| lines);
            if let Some(line) = before.lines().map(str::trim).find(|l| !l.is_empty()) {
                link.name = clean(line);
            }
        }
        return Some(link);
    }
    None
}

/// Whitespace-separated tokens with their byte offsets.
fn tokens(text: &str) -> impl Iterator<Item = (usize, &str)> {
    text.split_whitespace()
        .map(move |t| (t.as_ptr() as usize - text.as_ptr() as usize, t))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Point, Provider};

    #[test]
    fn cleaning() {
        assert_eq!(clean("  Café \n Sprüngli\t"), Some("Café Sprüngli".into()));
        assert_eq!(clean("a\u{0}b"), Some("a b".into()));
        assert_eq!(clean(" \n "), None);
        let long = "x".repeat(500);
        assert_eq!(clean(&long).unwrap().chars().count(), MAX_NAME_CHARS);
        let words = "ab ".repeat(200);
        assert!(clean(&words).unwrap().chars().count() <= MAX_NAME_CHARS);
    }

    #[test]
    fn shared_text() {
        let link = find_in_text("Café Sprüngli\nBahnhofstrasse 21\nhttps://maps.app.goo.gl/abc123")
            .unwrap();
        assert!(link.needs_redirect);
        assert_eq!(link.name.as_deref(), Some("Café Sprüngli"));
        assert_eq!(link.original, "https://maps.app.goo.gl/abc123");

        let link = find_in_text("Look here: geo:47.37,8.54.").unwrap();
        assert_eq!(link.provider, Provider::Geo);
        assert_eq!(link.point, Point::new(47.37, 8.54));
        assert_eq!(link.name, None);

        let link = find_in_text("(https://omaps.app/B4srhdHVVt/Berlin)").unwrap();
        assert_eq!(link.name.as_deref(), Some("Berlin"));

        assert_eq!(find_in_text("no link here https://example.com"), None);
        assert_eq!(find_in_text(""), None);
    }
}
