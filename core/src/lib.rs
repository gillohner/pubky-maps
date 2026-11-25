//! Pubky Maps core: sign-in with Pubky Ring, homeserver storage and the social objects the
//! app writes about places (tags, reviews, photos, lists, saved places), exposed to Kotlin
//! through UniFFI, plus the map-link reader (`map-links`) with short-link following and the
//! Pinakes place lookup.
//!
//! The app builds one [`PubkyMapsCore`] from a [`CoreConfig`]. Pure helpers (place URIs,
//! object builders) are free functions and need no network.

mod auth;
mod config;
mod core;
mod error;
mod links;
mod objects;
mod place;
mod secret;
mod signin;

pub use crate::auth::{
    APP_PRIVATE_DIR, AuthCallback, AuthFlowType, AuthRequest, CALLBACK_HOST, CALLBACK_SCHEME,
    CLIENT_ID, parse_auth_callback, requested_capabilities,
};
pub use crate::config::{CoreConfig, DEFAULT_REQUEST_TIMEOUT_MS, default_core_config};
pub use crate::core::{
    ListEntry, ListOptions, ListPage, PhotoPost, PubkyMapsCore, SavedPlace, SavedPlacePage,
    SessionInfo, SignedIn,
};
pub use crate::error::CoreError;
pub use crate::links::{
    LinkPlace, LinkedPlace, MapApp, MapLink, MapPoint, MapProvider, OsmElementRef, TravelMode,
    build_directions_link, build_place_link, find_map_link, map_link_place_uri, parse_map_link,
    place_from_text,
};
pub use crate::objects::{
    BuiltFile, BuiltObject, CollectionEdit, CollectionItem, build_bookmark, build_collection,
    build_photo_file, build_photo_post, build_review, build_tag, edit_collection,
};
pub use crate::place::{
    GEO_MAX_FRACTION_DIGITS, OsmElement, geo_place_uri, normalize_place_uri, osm_place_uri,
};
pub use crate::secret::SecretValue;
pub use crate::signin::SignInEvent;

uniffi::setup_scaffolding!();
