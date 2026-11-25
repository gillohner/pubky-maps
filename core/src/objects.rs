//! Social objects about places, built and validated by `pubky-social-specs`.
//!
//! Ids, paths and validation all come from the specs crate; nothing here hashes or encodes
//! an id by hand. Every builder normalises the place first (see [`crate::place`]).

use pubky_social_specs::traits::{HasIdPath, HashId, Validatable};
use pubky_social_specs::{
    CreatedBookmark, PUB_CTX, ParsedUri, PostEnvelope, PubkyId, PubkySocialAttachment,
    PubkySocialBookmark, PubkySocialCollectionContent, PubkySocialCollectionItem, PubkySocialFile,
    PubkySocialPost, PubkySocialPostKind, PubkySocialTag, Resource, Root, VALIDATION_LIMITS,
    bookmark_target, bookmark_uri_builder, create_bookmark, essence, file_uri_builder,
    post_uri_builder, social_path, tag_uri_builder,
};

use crate::error::CoreError;
use crate::place::normalize_place_uri;

/// A JSON object ready to PUT: its id, storage path, `pubky://` URI and body.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BuiltObject {
    /// The object id: a tag's hash id, a post's timestamp id, a bookmark's filename.
    pub id: String,
    /// For posts: the id of this version (`editId`). Equal to `id` for a new post.
    pub edit_id: Option<String>,
    /// Storage path on the author's homeserver, e.g. `/pub/social/v1/tags/{id}.json`.
    pub path: String,
    /// The object's URI. For posts this is the versionless post reference.
    pub uri: String,
    /// The JSON body.
    pub json: String,
}

/// An uploaded file's address.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BuiltFile {
    /// Content hash id of the bytes.
    pub id: String,
    /// Storage path, `/pub/social/v1/files/{id}.{ext}`.
    pub path: String,
    /// `pubky://` URI used as the post attachment.
    pub uri: String,
    /// The MIME type detected from the bytes.
    pub mime_type: String,
}

/// One entry of a list (collection post).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CollectionItem {
    pub uri: String,
    pub note: Option<String>,
}

/// A change to a list.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum CollectionEdit {
    /// Add a place; a place already in the list is left as it is.
    Add { place: String, note: Option<String> },
    /// Remove a place; a place not in the list is a no-op.
    Remove { place: String },
}

pub(crate) fn validation(e: String) -> CoreError {
    CoreError::validation(e)
}

pub(crate) fn owner_id(author: &str) -> Result<PubkyId, CoreError> {
    PubkyId::try_from(author).map_err(|e| CoreError::validation(format!("author: {e}")))
}

fn to_json<T: serde::Serialize>(value: &T) -> Result<String, CoreError> {
    serde_json::to_string(value).map_err(|e| CoreError::internal(format!("JSON: {e}")))
}

// --- Tags -------------------------------------------------------------------------------

pub(crate) fn tag_object(place: &str, label: &str) -> Result<(PubkySocialTag, String), CoreError> {
    let target = normalize_place_uri(place.to_string())?;
    let tag = PubkySocialTag::new(target, label.to_string());
    let id = tag.create_id();
    tag.validate(Some(&id), &PUB_CTX).map_err(validation)?;
    Ok((tag, id))
}

fn built_tag(author: &str, tag: &PubkySocialTag, id: String) -> Result<BuiltObject, CoreError> {
    Ok(BuiltObject {
        path: PubkySocialTag::create_path(&id),
        uri: tag_uri_builder(author.to_string(), id.clone()),
        json: to_json(tag)?,
        id,
        edit_id: None,
    })
}

/// A tag `label` on a place: `/pub/social/v1/tags/{HashId(place:label)}.json`. The label is
/// trimmed and ASCII-lowercased; 1 to 20 characters, no whitespace, `:` or `,`.
#[uniffi::export]
pub fn build_tag(author: String, place: String, label: String) -> Result<BuiltObject, CoreError> {
    owner_id(&author)?;
    let (tag, id) = tag_object(&place, &label)?;
    built_tag(&author, &tag, id)
}

/// The tag to store over whatever is at its path, keeping unknown members of a stored copy.
/// The flag is `false` when the stored tag is already this tag (skip the write); the object
/// is then the stored one.
pub(crate) fn merge_tag(
    author: &str,
    fresh: PubkySocialTag,
    id: String,
    existing: Option<&[u8]>,
) -> Result<(BuiltObject, bool), CoreError> {
    let stored = existing.and_then(|bytes| serde_json::from_slice::<PubkySocialTag>(bytes).ok());
    let Some(stored) = stored else {
        return Ok((built_tag(author, &fresh, id)?, true));
    };
    if stored.uri == fresh.uri
        && stored.label == fresh.label
        && stored.validate(Some(&id), &PUB_CTX).is_ok()
    {
        return Ok((built_tag(author, &stored, id)?, false));
    }
    let merged = PubkySocialTag {
        extra: stored.extra,
        ..fresh.clone()
    };
    let chosen = if merged.validate(Some(&id), &PUB_CTX).is_ok() {
        merged
    } else {
        fresh
    };
    Ok((built_tag(author, &chosen, id)?, true))
}

// --- Posts ------------------------------------------------------------------------------

fn built_post(
    author: &str,
    post: &PubkySocialPost,
    id: String,
    edit_id: String,
    path: String,
) -> Result<BuiltObject, CoreError> {
    Ok(BuiltObject {
        uri: post_uri_builder(author.to_string(), id.clone()),
        json: to_json(post)?,
        id,
        edit_id: Some(edit_id),
        path,
    })
}

fn mint_new_post(author: &str, post: &PubkySocialPost) -> Result<BuiltObject, CoreError> {
    mint_new_post_in(Root::Pub, author, post)
}

fn mint_new_post_in(
    root: Root,
    author: &str,
    post: &PubkySocialPost,
) -> Result<BuiltObject, CoreError> {
    let owner = owner_id(author)?;
    let minted = post
        .create_version(root, &owner, None)
        .map_err(validation)?;
    let mut built = built_post(author, post, minted.id, minted.edit_id, minted.path)?;
    built.uri = post_uri_in(root, author, &built.id);
    Ok(built)
}

/// The versionless reference of a post: `pubky://{author}/{pub|priv}/social/v1/posts/{id}`.
pub(crate) fn post_uri_in(root: Root, author: &str, post_id: &str) -> String {
    match root {
        Root::Pub => post_uri_builder(author.to_string(), post_id.to_string()),
        Root::Priv => format!(
            "pubky://{author}{}",
            social_path(Root::Priv, &format!("posts/{post_id}"))
        ),
    }
}

/// `/priv/...` for a private object, `/pub/...` otherwise.
pub(crate) fn root_of(private: bool) -> Root {
    if private { Root::Priv } else { Root::Pub }
}

/// A review: a public `note` post whose `parent` is the place.
#[uniffi::export]
pub fn build_review(author: String, place: String, text: String) -> Result<BuiltObject, CoreError> {
    let target = normalize_place_uri(place)?;
    let post = PubkySocialPost::new(text, PubkySocialPostKind::Note, Some(target), None, vec![]);
    mint_new_post(&author, &post)
}

/// The image types a photo may have. HEIC/HEIF is not in the specs' frozen MIME map.
const PHOTO_TYPES: [&str; 3] = ["image/jpeg", "image/png", "image/webp"];

/// The image type the bytes start with, or why they are refused.
fn sniff_photo(bytes: &[u8]) -> Result<&'static str, CoreError> {
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Ok("image/jpeg");
    }
    if bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
        return Ok("image/png");
    }
    if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        return Ok("image/webp");
    }
    if bytes.len() >= 12 && &bytes[4..8] == b"ftyp" {
        let brand = &bytes[8..12];
        if [
            b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis", b"mif1", b"msf1",
        ]
        .iter()
        .any(|b| brand == *b)
        {
            return Err(heic_error());
        }
    }
    Err(CoreError::validation(
        "unsupported photo format: use JPEG, PNG or WebP",
    ))
}

fn heic_error() -> CoreError {
    CoreError::validation("HEIC/HEIF photos are not supported: convert to JPEG or WebP first")
}

/// The file object of a photo: `/pub/social/v1/files/{blake3}.{jpg|png|webp}`. The type is
/// detected from the bytes and must match `mime_type`; HEIC is refused. Strip EXIF before.
pub(crate) fn photo_file(
    author: &str,
    bytes: Vec<u8>,
    mime_type: &str,
) -> Result<(BuiltFile, Vec<u8>), CoreError> {
    owner_id(author)?;
    let declared = essence(mime_type)
        .ok_or_else(|| CoreError::validation(format!("not a MIME type: {mime_type}")))?;
    if declared == "image/heic"
        || declared == "image/heif"
        || declared.starts_with("image/heic-")
        || declared.starts_with("image/heif-")
    {
        return Err(heic_error());
    }
    let detected = sniff_photo(&bytes)?;
    if declared != detected {
        return Err(CoreError::validation(format!(
            "photo is {detected} but was declared as {declared}"
        )));
    }
    debug_assert!(PHOTO_TYPES.contains(&detected));
    let created = PubkySocialFile::create_file(bytes, detected, Root::Pub).map_err(validation)?;
    let filename = created
        .path
        .rsplit('/')
        .next()
        .unwrap_or_default()
        .to_string();
    let file = BuiltFile {
        uri: file_uri_builder(author.to_string(), filename),
        id: created.id,
        path: created.path,
        mime_type: detected.to_string(),
    };
    Ok((file, created.file.0))
}

/// The address a photo will be uploaded to (no bytes are kept).
#[uniffi::export]
pub fn build_photo_file(
    author: String,
    image: Vec<u8>,
    mime_type: String,
) -> Result<BuiltFile, CoreError> {
    photo_file(&author, image, &mime_type).map(|(file, _)| file)
}

/// A photo of a place: an `image` post with `embed` = the place and the uploaded file as its
/// attachment. Upload the file before writing the post.
#[uniffi::export]
pub fn build_photo_post(
    author: String,
    place: String,
    file_uri: String,
    alt: Option<String>,
    caption: Option<String>,
) -> Result<BuiltObject, CoreError> {
    let target = normalize_place_uri(place)?;
    let attachment = PubkySocialAttachment::new(file_uri, alt, None);
    let post = PubkySocialPost::new(
        caption.unwrap_or_default(),
        PubkySocialPostKind::Image,
        None,
        Some(target),
        vec![attachment],
    );
    mint_new_post(&author, &post)
}

// --- Lists ------------------------------------------------------------------------------

fn list_full_error() -> CoreError {
    CoreError::validation(format!(
        "a list holds at most {} places",
        VALIDATION_LIMITS.collection_items_max_count
    ))
}

fn collection_post(content: &PubkySocialCollectionContent) -> Result<PubkySocialPost, CoreError> {
    if content.items.len() > VALIDATION_LIMITS.collection_items_max_count {
        return Err(list_full_error());
    }
    Ok(PubkySocialPost::new(
        to_json(content)?,
        PubkySocialPostKind::Collection,
        None,
        None,
        vec![],
    ))
}

/// A public list: a `collection` post with up to 100 items.
#[uniffi::export]
pub fn build_collection(
    author: String,
    name: String,
    description: Option<String>,
    items: Vec<CollectionItem>,
) -> Result<BuiltObject, CoreError> {
    build_collection_in(Root::Pub, author, name, description, items)
}

/// A list, public under `/pub` or with `private` under `/priv` (the same object; `/priv` is
/// alpha and readable by the homeserver operator). Its id is minted now, so the app can queue
/// it and send it later to the same path.
#[uniffi::export]
pub fn build_list(
    author: String,
    name: String,
    description: Option<String>,
    items: Vec<CollectionItem>,
    private: bool,
) -> Result<BuiltObject, CoreError> {
    build_collection_in(root_of(private), author, name, description, items)
}

/// A list under `root`: `/pub` (public) or `/priv` (private, the same object; `/priv` is
/// alpha and readable by the homeserver operator).
pub(crate) fn build_collection_in(
    root: Root,
    author: String,
    name: String,
    description: Option<String>,
    items: Vec<CollectionItem>,
) -> Result<BuiltObject, CoreError> {
    let items = items
        .into_iter()
        .map(|item| {
            Ok(PubkySocialCollectionItem::new(
                normalize_place_uri(item.uri)?,
                item.note,
            ))
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    let content = PubkySocialCollectionContent {
        name,
        description,
        items,
        cover_image: None,
        layout: None,
        extra: Default::default(),
    };
    mint_new_post_in(root, &author, &collection_post(&content)?)
}

/// `/{pub|priv}/social/v1/posts/{id}/`, the folder holding every version of a post.
pub(crate) fn post_versions_dir(root: Root, post_id: &str) -> Result<String, CoreError> {
    pubky_social_specs::validate_timestamp_id_format(post_id).map_err(validation)?;
    Ok(social_path(root, &format!("posts/{post_id}/")))
}

/// The post id of a post folder path (`/{root}/social/v1/posts/{id}/`), if it is one.
pub(crate) fn post_folder_id(root: Root, path: &str) -> Option<String> {
    let prefix = social_path(root, "posts/");
    let id = path.strip_prefix(&prefix)?.strip_suffix('/')?;
    pubky_social_specs::validate_timestamp_id_format(id).ok()?;
    Some(id.to_string())
}

/// A stored list read back: its name, description and items, or `None` when the post is
/// not a list (or not readable as one).
pub(crate) fn collection_of(post_json: &str) -> Option<PubkySocialCollectionContent> {
    let post: PubkySocialPost = serde_json::from_str(post_json).ok()?;
    if post.kind != PubkySocialPostKind::Collection {
        return None;
    }
    serde_json::from_str(&post.content).ok()
}

/// The kind of a stored post, `None` when it is not a post.
pub(crate) fn post_kind_of(post_json: &str) -> Option<PubkySocialPostKind> {
    serde_json::from_str::<PubkySocialPost>(post_json)
        .ok()
        .map(|post| post.kind)
}

/// The `(id, editId)` of a stored post version URI, if it is one.
pub(crate) fn post_version_of(uri: &str) -> Option<(String, String)> {
    match ParsedUri::try_from(uri).ok()?.resource {
        Resource::Post {
            id,
            version: Some(version),
            ..
        } => Some((id, version)),
        _ => None,
    }
}

/// A new version of a list with one edit applied to the newest stored version
/// (`head_edit_id`, `head_json`). `None` when the edit changes nothing. Unknown members of
/// the post, the list and its items are kept.
#[uniffi::export]
pub fn edit_collection(
    author: String,
    post_id: String,
    head_edit_id: String,
    head_json: String,
    edit: CollectionEdit,
) -> Result<Option<BuiltObject>, CoreError> {
    edit_collection_in(Root::Pub, author, post_id, head_edit_id, head_json, edit)
}

/// [`edit_collection`] for a list under `root`.
pub(crate) fn edit_collection_in(
    root: Root,
    author: String,
    post_id: String,
    head_edit_id: String,
    head_json: String,
    edit: CollectionEdit,
) -> Result<Option<BuiltObject>, CoreError> {
    let owner = owner_id(&author)?;
    let head: PubkySocialPost = serde_json::from_str(&head_json)
        .map_err(|e| CoreError::validation(format!("stored list is not a post: {e}")))?;
    if head.kind != PubkySocialPostKind::Collection {
        return Err(CoreError::validation(format!(
            "post {post_id} is a {} post, not a list",
            head.kind
        )));
    }
    let mut content: PubkySocialCollectionContent = serde_json::from_str(&head.content)
        .map_err(|e| CoreError::validation(format!("stored list content: {e}")))?;
    let changed = match edit {
        CollectionEdit::Add { place, note } => {
            let uri = normalize_place_uri(place)?;
            if content.items.iter().any(|item| item.uri == uri) {
                false
            } else {
                if content.items.len() >= VALIDATION_LIMITS.collection_items_max_count {
                    return Err(list_full_error());
                }
                content
                    .items
                    .push(PubkySocialCollectionItem::new(uri, note));
                true
            }
        }
        CollectionEdit::Remove { place } => {
            let normalized = normalize_place_uri(place.clone()).ok();
            let before = content.items.len();
            content
                .items
                .retain(|item| item.uri != place && Some(&item.uri) != normalized.as_ref());
            content.items.len() != before
        }
    };
    if !changed {
        return Ok(None);
    }
    let post = PostEnvelope {
        content: collection_post(&content)?.content,
        ..head
    };
    let minted = post
        .edit_version(&post_id, &head_edit_id, root, &owner, None)
        .map_err(validation)?;
    let mut built = built_post(&author, &post, minted.id, minted.edit_id, minted.path)?;
    built.uri = post_uri_in(root, &author, &built.id);
    Ok(Some(built))
}

// --- Bookmarks --------------------------------------------------------------------------

fn built_bookmark(author: &str, created: &CreatedBookmark) -> Result<BuiltObject, CoreError> {
    Ok(BuiltObject {
        id: created.filename.clone(),
        edit_id: None,
        path: created.path.clone(),
        uri: bookmark_uri_builder(author.to_string(), created.filename.clone()),
        json: to_json(&created.bookmark)?,
    })
}

pub(crate) fn bookmark_object(place: &str) -> Result<CreatedBookmark, CoreError> {
    let target = normalize_place_uri(place.to_string())?;
    create_bookmark(&target).map_err(validation)
}

/// A private saved place: `/priv/social/v1/bookmarks/{base64url(place)}.json`, or the
/// `~{HashId}.json` form with `target` in the body when the place is over 187 bytes.
#[uniffi::export]
pub fn build_bookmark(author: String, place: String) -> Result<BuiltObject, CoreError> {
    owner_id(&author)?;
    built_bookmark(&author, &bookmark_object(&place)?)
}

/// The bookmark to store over whatever is at its path, keeping unknown members of a stored
/// copy. The flag is `false` when it is already stored (skip the write).
pub(crate) fn merge_bookmark(
    author: &str,
    fresh: CreatedBookmark,
    existing: Option<&[u8]>,
) -> Result<(BuiltObject, bool), CoreError> {
    let stored =
        existing.and_then(|bytes| serde_json::from_slice::<PubkySocialBookmark>(bytes).ok());
    let Some(stored) = stored else {
        return Ok((built_bookmark(author, &fresh)?, true));
    };
    let ctx = pubky_social_specs::ValidationCtx { root: Root::Priv };
    if stored.validate(Some(&fresh.filename), &ctx).is_ok() {
        let kept = CreatedBookmark {
            bookmark: stored,
            ..fresh
        };
        return Ok((built_bookmark(author, &kept)?, false));
    }
    let merged = CreatedBookmark {
        bookmark: PubkySocialBookmark {
            extra: stored.extra,
            ..fresh.bookmark.clone()
        },
        ..fresh.clone()
    };
    let chosen = if merged
        .bookmark
        .validate(Some(&merged.filename), &ctx)
        .is_ok()
    {
        merged
    } else {
        fresh
    };
    Ok((built_bookmark(author, &chosen)?, true))
}

/// The leaf name of a bookmark path without `.json`, if it is a bookmark file.
pub(crate) fn bookmark_filename_of(path: &str) -> Option<&str> {
    path.rsplit('/').next()?.strip_suffix(".json")
}

/// Whether reading this bookmark's target needs its body (the long `~` form).
pub(crate) fn bookmark_needs_body(filename: &str) -> bool {
    filename.starts_with('~')
}

/// The place a stored bookmark points at. `body` is needed only for the `~` form.
pub(crate) fn bookmark_target_of(filename: &str, body: Option<&[u8]>) -> Result<String, CoreError> {
    let content = match body {
        Some(bytes) => serde_json::from_slice::<PubkySocialBookmark>(bytes)
            .map_err(|e| CoreError::validation(format!("bookmark body: {e}")))?,
        None => PubkySocialBookmark::default(),
    };
    bookmark_target(filename, &content).map_err(validation)
}

#[cfg(test)]
mod tests {
    use super::*;
    use pubky_social_specs::traits::TimestampId;

    const AUTHOR: &str = "operrr8wsbpr3ue9d4qj41ge1kcc6r7fdiy6o3ugjrrhi4y77rdo";
    const PLACE: &str = "https://www.openstreetmap.org/node/270803095";

    #[test]
    fn tag_id_and_path_match_the_specs() {
        let built = build_tag(AUTHOR.into(), PLACE.into(), "Cappuccino".into()).unwrap();
        let expected = PubkySocialTag {
            uri: PLACE.into(),
            label: "cappuccino".into(),
            created_at: 0,
            extra: Default::default(),
        };
        assert_eq!(built.id, expected.create_id());
        assert_eq!(built.path, format!("/pub/social/v1/tags/{}.json", built.id));
        assert_eq!(
            built.uri,
            format!("pubky://{AUTHOR}/pub/social/v1/tags/{}.json", built.id)
        );
        let json: serde_json::Value = serde_json::from_str(&built.json).unwrap();
        assert_eq!(json["uri"], PLACE);
        assert_eq!(json["label"], "cappuccino");
        assert!(json["created_at"].as_i64().unwrap() > 0);
        // The stored bytes validate under the id, as an indexer would check them.
        <PubkySocialTag as Validatable>::try_from(built.json.as_bytes(), &built.id, &PUB_CTX)
            .unwrap();
    }

    #[test]
    fn tag_normalises_the_place_first() {
        let a = build_tag(AUTHOR.into(), PLACE.into(), "cafe".into()).unwrap();
        let b = build_tag(
            AUTHOR.into(),
            "https://osm.org/node/270803095/".into(),
            "cafe".into(),
        )
        .unwrap();
        assert_eq!(a.id, b.id);
        let geo = build_tag(
            AUTHOR.into(),
            "geo:47.376871,8.541694".into(),
            "hazard".into(),
        )
        .unwrap();
        let json: serde_json::Value = serde_json::from_str(&geo.json).unwrap();
        assert_eq!(json["uri"], "geo:47.37687,8.54169");
    }

    #[test]
    fn tag_rejects_bad_labels_and_places() {
        for label in ["", "two words", "a:b", "a,b", "abcdefghijklmnopqrstu"] {
            assert!(
                matches!(
                    build_tag(AUTHOR.into(), PLACE.into(), label.into()),
                    Err(CoreError::Validation { .. })
                ),
                "{label:?}"
            );
        }
        assert!(build_tag(AUTHOR.into(), "not a uri".into(), "ok".into()).is_err());
        assert!(build_tag("not-a-key".into(), PLACE.into(), "ok".into()).is_err());
        // 20 characters is the maximum.
        assert!(build_tag(AUTHOR.into(), PLACE.into(), "abcdefghijklmnopqrst".into()).is_ok());
    }

    #[test]
    fn tag_merge_keeps_unknown_members_and_skips_identical_writes() {
        let (fresh, id) = tag_object(PLACE, "cafe").unwrap();
        let mut stored: serde_json::Value = serde_json::to_value(&fresh).unwrap();
        stored["created_at"] = 1_700_000_000_000_000_i64.into();
        let same = serde_json::to_vec(&stored).unwrap();
        let (kept, write) = merge_tag(AUTHOR, fresh.clone(), id.clone(), Some(&same)).unwrap();
        assert!(!write);
        assert!(kept.json.contains("1700000000000000"), "{}", kept.json);

        // A stored copy with another label (written by an older client) under this id is
        // rewritten, and its unknown members survive.
        stored["label"] = "Cafe".into();
        stored["ext"] = serde_json::json!({"app": "other"});
        let other = serde_json::to_vec(&stored).unwrap();
        let (built, write) = merge_tag(AUTHOR, fresh.clone(), id.clone(), Some(&other)).unwrap();
        assert!(write);
        let json: serde_json::Value = serde_json::from_str(&built.json).unwrap();
        assert_eq!(json["ext"]["app"], "other");
        assert_eq!(json["label"], "cafe");

        let (built, write) = merge_tag(AUTHOR, fresh, id, Some(b"not json")).unwrap();
        assert!(write);
        assert!(built.json.contains("\"cafe\""));
    }

    #[test]
    fn review_is_a_note_replying_to_the_place() {
        let built = build_review(AUTHOR.into(), PLACE.into(), " Great coffee. ".into()).unwrap();
        let edit_id = built.edit_id.clone().unwrap();
        assert_eq!(edit_id, built.id);
        assert_eq!(
            built.path,
            format!("/pub/social/v1/posts/{}/{}.json", built.id, built.id)
        );
        assert_eq!(
            built.uri,
            format!("pubky://{AUTHOR}/pub/social/v1/posts/{}", built.id)
        );
        let json: serde_json::Value = serde_json::from_str(&built.json).unwrap();
        assert_eq!(json["kind"], "note");
        assert_eq!(json["parent"], PLACE);
        assert_eq!(json["content"], "Great coffee.");
        assert!(json["embed"].is_null());
        let post: PubkySocialPost = serde_json::from_str(&built.json).unwrap();
        post.validate_id(&built.id).unwrap();
        post.validate(Some(&built.id), &PUB_CTX).unwrap();
        assert_eq!(
            post_version_of(&format!("pubky://{AUTHOR}{}", built.path)),
            Some((built.id.clone(), edit_id))
        );
    }

    #[test]
    fn review_rejects_empty_and_too_long_text() {
        assert!(build_review(AUTHOR.into(), PLACE.into(), "   ".into()).is_err());
        assert!(build_review(AUTHOR.into(), PLACE.into(), "x".repeat(2001)).is_err());
        assert!(build_review(AUTHOR.into(), PLACE.into(), "x".repeat(2000)).is_ok());
    }

    fn jpeg() -> Vec<u8> {
        let mut bytes = vec![0xFF, 0xD8, 0xFF, 0xE0];
        bytes.extend_from_slice(b"JFIF fake jpeg body");
        bytes
    }

    #[test]
    fn photo_file_is_content_addressed() {
        let file = build_photo_file(AUTHOR.into(), jpeg(), "image/jpeg".into()).unwrap();
        let expected = PubkySocialFile(jpeg()).create_id();
        assert_eq!(file.id, expected);
        assert_eq!(file.path, format!("/pub/social/v1/files/{expected}.jpg"));
        assert_eq!(
            file.uri,
            format!("pubky://{AUTHOR}/pub/social/v1/files/{expected}.jpg")
        );
        assert_eq!(file.mime_type, "image/jpeg");

        let mut png = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
        png.extend_from_slice(b"rest");
        let file = build_photo_file(AUTHOR.into(), png, "image/png".into()).unwrap();
        assert!(file.path.ends_with(".png"));

        let mut webp = b"RIFF\x10\x00\x00\x00WEBPVP8 ".to_vec();
        webp.extend_from_slice(b"rest");
        let file = build_photo_file(AUTHOR.into(), webp, "image/webp".into()).unwrap();
        assert!(file.path.ends_with(".webp"));
    }

    #[test]
    fn photo_rejects_heic_and_mismatches() {
        let mut heic = vec![0, 0, 0, 0x18];
        heic.extend_from_slice(b"ftypheic\0\0\0\0mif1heic");
        for mime in ["image/heic", "image/jpeg"] {
            let err = build_photo_file(AUTHOR.into(), heic.clone(), mime.into()).unwrap_err();
            assert!(err.to_string().contains("HEIC"), "{mime}: {err}");
        }
        let err = build_photo_file(AUTHOR.into(), jpeg(), "image/heif".into()).unwrap_err();
        assert!(err.to_string().contains("HEIC"), "{err}");
        assert!(build_photo_file(AUTHOR.into(), jpeg(), "image/png".into()).is_err());
        assert!(
            build_photo_file(AUTHOR.into(), b"GIF89a....".to_vec(), "image/gif".into()).is_err()
        );
        assert!(build_photo_file(AUTHOR.into(), vec![], "image/jpeg".into()).is_err());
    }

    #[test]
    fn photo_post_embeds_the_place() {
        let file = build_photo_file(AUTHOR.into(), jpeg(), "image/jpeg".into()).unwrap();
        let built = build_photo_post(
            AUTHOR.into(),
            "geo:47.37687,8.54169".into(),
            file.uri.clone(),
            Some("The terrace".into()),
            None,
        )
        .unwrap();
        let json: serde_json::Value = serde_json::from_str(&built.json).unwrap();
        assert_eq!(json["kind"], "image");
        assert_eq!(json["embed"], "geo:47.37687,8.54169");
        assert!(json["parent"].is_null());
        assert_eq!(json["attachments"][0]["uri"], file.uri);
        assert_eq!(json["attachments"][0]["alt"], "The terrace");
        assert_eq!(json["content"], "");
    }

    fn items(n: usize) -> Vec<CollectionItem> {
        (1..=n)
            .map(|i| CollectionItem {
                uri: format!("https://www.openstreetmap.org/node/{i}"),
                note: None,
            })
            .collect()
    }

    #[test]
    fn collection_holds_up_to_100_places() {
        let built = build_collection(
            AUTHOR.into(),
            "Cafés".into(),
            Some("Good ones".into()),
            items(2),
        )
        .unwrap();
        let json: serde_json::Value = serde_json::from_str(&built.json).unwrap();
        assert_eq!(json["kind"], "collection");
        let content: serde_json::Value =
            serde_json::from_str(json["content"].as_str().unwrap()).unwrap();
        assert_eq!(content["name"], "Cafés");
        assert_eq!(
            content["items"][1]["uri"],
            "https://www.openstreetmap.org/node/2"
        );
        assert!(build_collection(AUTHOR.into(), "Full".into(), None, items(100)).is_ok());
        let err = build_collection(AUTHOR.into(), "Too big".into(), None, items(101)).unwrap_err();
        assert!(err.to_string().contains("100"), "{err}");
        assert!(build_collection(AUTHOR.into(), "  ".into(), None, items(1)).is_err());
    }

    #[test]
    fn collection_edits_make_new_versions() {
        let built = build_collection(AUTHOR.into(), "Cafés".into(), None, items(1)).unwrap();
        let head_edit = built.edit_id.clone().unwrap();
        let added = edit_collection(
            AUTHOR.into(),
            built.id.clone(),
            head_edit.clone(),
            built.json.clone(),
            CollectionEdit::Add {
                place: "https://osm.org/way/7".into(),
                note: Some("roof bar".into()),
            },
        )
        .unwrap()
        .unwrap();
        assert_eq!(added.id, built.id);
        let new_edit = added.edit_id.clone().unwrap();
        assert!(new_edit > head_edit, "{new_edit} > {head_edit}");
        assert_eq!(
            added.path,
            format!("/pub/social/v1/posts/{}/{new_edit}.json", built.id)
        );
        let post: PubkySocialPost = serde_json::from_str(&added.json).unwrap();
        let content: PubkySocialCollectionContent = serde_json::from_str(&post.content).unwrap();
        assert_eq!(content.items.len(), 2);
        assert_eq!(content.items[1].uri, "https://www.openstreetmap.org/way/7");
        assert_eq!(content.items[1].note.as_deref(), Some("roof bar"));

        // Adding a place already there, or removing one that is not, changes nothing.
        let again = edit_collection(
            AUTHOR.into(),
            built.id.clone(),
            new_edit.clone(),
            added.json.clone(),
            CollectionEdit::Add {
                place: "https://www.openstreetmap.org/way/7".into(),
                note: None,
            },
        )
        .unwrap();
        assert_eq!(again, None);
        let removed = edit_collection(
            AUTHOR.into(),
            built.id.clone(),
            new_edit.clone(),
            added.json.clone(),
            CollectionEdit::Remove {
                place: "https://www.openstreetmap.org/node/1".into(),
            },
        )
        .unwrap()
        .unwrap();
        let post: PubkySocialPost = serde_json::from_str(&removed.json).unwrap();
        let content: PubkySocialCollectionContent = serde_json::from_str(&post.content).unwrap();
        assert_eq!(content.items.len(), 1);
        let none = edit_collection(
            AUTHOR.into(),
            built.id,
            new_edit,
            added.json,
            CollectionEdit::Remove {
                place: "https://www.openstreetmap.org/node/99".into(),
            },
        )
        .unwrap();
        assert_eq!(none, None);
    }

    #[test]
    fn collection_edit_keeps_unknown_members_and_respects_the_cap() {
        let built = build_collection(AUTHOR.into(), "Full".into(), None, items(100)).unwrap();
        let head = built.edit_id.clone().unwrap();
        let err = edit_collection(
            AUTHOR.into(),
            built.id.clone(),
            head.clone(),
            built.json.clone(),
            CollectionEdit::Add {
                place: "geo:1,1".into(),
                note: None,
            },
        )
        .unwrap_err();
        assert!(err.to_string().contains("100"), "{err}");

        let mut post: serde_json::Value = serde_json::from_str(&built.json).unwrap();
        let mut content: serde_json::Value =
            serde_json::from_str(post["content"].as_str().unwrap()).unwrap();
        content["ext"] = serde_json::json!({"color": "red"});
        content["items"] = serde_json::json!([{"uri": "geo:1,1", "ext": {"star": true}}]);
        post["content"] = content.to_string().into();
        post["ext"] = serde_json::json!({"pinned": true});
        let edited = edit_collection(
            AUTHOR.into(),
            built.id,
            head,
            post.to_string(),
            CollectionEdit::Add {
                place: "geo:2,2".into(),
                note: None,
            },
        )
        .unwrap()
        .unwrap();
        let post: serde_json::Value = serde_json::from_str(&edited.json).unwrap();
        assert_eq!(post["ext"]["pinned"], true);
        let content: serde_json::Value =
            serde_json::from_str(post["content"].as_str().unwrap()).unwrap();
        assert_eq!(content["ext"]["color"], "red");
        assert_eq!(content["items"][0]["ext"]["star"], true);
        assert_eq!(content["items"][1]["uri"], "geo:2,2");
    }

    #[test]
    fn edit_refuses_non_lists() {
        let review = build_review(AUTHOR.into(), PLACE.into(), "hi".into()).unwrap();
        let err = edit_collection(
            AUTHOR.into(),
            review.id.clone(),
            review.id,
            review.json,
            CollectionEdit::Remove {
                place: PLACE.into(),
            },
        )
        .unwrap_err();
        assert!(err.to_string().contains("not a list"), "{err}");
    }

    #[test]
    fn bookmark_filename_follows_the_spec() {
        let built = build_bookmark(AUTHOR.into(), PLACE.into()).unwrap();
        use base64::Engine;
        let expected = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(PLACE);
        assert_eq!(built.id, expected);
        assert_eq!(
            built.path,
            format!("/priv/social/v1/bookmarks/{expected}.json")
        );
        assert_eq!(
            built.uri,
            format!("pubky://{AUTHOR}/priv/social/v1/bookmarks/{expected}.json")
        );
        let json: serde_json::Value = serde_json::from_str(&built.json).unwrap();
        assert!(json.get("target").is_none());
        assert_eq!(bookmark_filename_of(&built.path), Some(expected.as_str()));
        assert!(!bookmark_needs_body(&expected));
        assert_eq!(bookmark_target_of(&expected, None).unwrap(), PLACE);
    }

    #[test]
    fn long_bookmark_targets_use_the_hash_form() {
        let long = format!("https://maps.example.com/place?q={}", "a".repeat(200));
        let built = build_bookmark(AUTHOR.into(), long.clone()).unwrap();
        assert!(built.id.starts_with('~'), "{}", built.id);
        assert_eq!(
            built.id,
            pubky_social_specs::bookmark_filename(&long).unwrap()
        );
        let json: serde_json::Value = serde_json::from_str(&built.json).unwrap();
        assert_eq!(json["target"], long);
        assert!(bookmark_needs_body(&built.id));
        assert_eq!(
            bookmark_target_of(&built.id, Some(built.json.as_bytes())).unwrap(),
            long
        );
        assert!(bookmark_target_of(&built.id, None).is_err());
    }

    #[test]
    fn bookmark_merge_skips_existing() {
        let fresh = bookmark_object(PLACE).unwrap();
        let stored = to_json(&PubkySocialBookmark {
            created_at: 5,
            target: None,
            extra: Default::default(),
        })
        .unwrap();
        let (kept, write) = merge_bookmark(AUTHOR, fresh.clone(), Some(stored.as_bytes())).unwrap();
        assert!(!write);
        assert_eq!(kept.json, stored);
        assert!(merge_bookmark(AUTHOR, fresh, None).unwrap().1);
    }

    #[test]
    fn versions_dir_checks_the_id() {
        let id = PubkySocialPost::default().create_id();
        assert_eq!(
            post_versions_dir(Root::Pub, &id).unwrap(),
            format!("/pub/social/v1/posts/{id}/")
        );
        assert_eq!(
            post_versions_dir(Root::Priv, &id).unwrap(),
            format!("/priv/social/v1/posts/{id}/")
        );
        assert!(post_versions_dir(Root::Pub, "../../x").is_err());
    }

    #[test]
    fn post_folders_name_their_post() {
        let id = PubkySocialPost::default().create_id();
        let folder = format!("/pub/social/v1/posts/{id}/");
        assert_eq!(post_folder_id(Root::Pub, &folder), Some(id.clone()));
        assert_eq!(post_folder_id(Root::Priv, &folder), None);
        assert_eq!(
            post_folder_id(Root::Priv, &format!("/priv/social/v1/posts/{id}/")),
            Some(id.clone())
        );
        assert_eq!(
            post_folder_id(Root::Pub, &format!("/pub/social/v1/posts/{id}/{id}.json")),
            None
        );
        assert_eq!(post_folder_id(Root::Pub, "/pub/social/v1/posts/x/"), None);
    }

    #[test]
    fn a_private_list_is_the_same_object_under_priv() {
        let public = build_collection(AUTHOR.into(), "Cafés".into(), None, items(2)).unwrap();
        let private =
            build_collection_in(Root::Priv, AUTHOR.into(), "Cafés".into(), None, items(2)).unwrap();
        assert!(
            private.path.starts_with("/priv/social/v1/posts/"),
            "{}",
            private.path
        );
        assert_eq!(
            private.uri,
            format!("pubky://{AUTHOR}/priv/social/v1/posts/{}", private.id)
        );
        assert_eq!(
            public.uri,
            format!("pubky://{AUTHOR}/pub/social/v1/posts/{}", public.id)
        );
        let content = collection_of(&private.json).expect("a list");
        assert_eq!(content.name, "Cafés");
        assert_eq!(content.items.len(), 2);
        assert_eq!(
            post_kind_of(&private.json),
            Some(PubkySocialPostKind::Collection)
        );

        let edited = edit_collection_in(
            Root::Priv,
            AUTHOR.into(),
            private.id.clone(),
            private.edit_id.clone().unwrap(),
            private.json.clone(),
            CollectionEdit::Remove {
                place: content.items[0].uri.clone(),
            },
        )
        .unwrap()
        .expect("changed");
        assert!(
            edited
                .path
                .starts_with(&format!("/priv/social/v1/posts/{}/", private.id))
        );
        assert_eq!(edited.uri, private.uri);
        assert_eq!(collection_of(&edited.json).unwrap().items.len(), 1);
    }

    #[test]
    fn build_list_mints_under_the_chosen_root() {
        let private = build_list(AUTHOR.into(), "Mine".into(), None, items(1), true).unwrap();
        assert!(
            private.path.starts_with("/priv/social/v1/posts/"),
            "{}",
            private.path
        );
        let public = build_list(AUTHOR.into(), "Ours".into(), None, items(1), false).unwrap();
        assert!(
            public.path.starts_with("/pub/social/v1/posts/"),
            "{}",
            public.path
        );
        assert!(build_list(AUTHOR.into(), "Big".into(), None, items(101), false).is_err());
    }

    #[test]
    fn only_lists_read_as_lists() {
        let review = build_review(
            AUTHOR.into(),
            "https://www.openstreetmap.org/node/1".into(),
            "Nice".into(),
        )
        .unwrap();
        assert!(collection_of(&review.json).is_none());
        assert_eq!(post_kind_of(&review.json), Some(PubkySocialPostKind::Note));
        assert!(collection_of("not json").is_none());
        assert_eq!(post_kind_of("[1]"), None);
    }
}
