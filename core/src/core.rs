//! `PubkyMapsCore`: the one object the app holds.

use std::future::Future;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use pubky::{Pubky, PubkyResource, PubkySession, PublicKey, SessionStorage};
use pubky_social_specs::traits::HasIdPath;
use pubky_social_specs::{
    PubkySocialBookmark, PubkySocialTag, Root, post_uri_builder, social_path,
};
use tokio::runtime::{Handle, Runtime};

use crate::auth::{AuthRequest, FlowSpec};
use crate::config::{CoreConfig, build_client};
use crate::error::{CoreError, redact};
use crate::objects::{
    self, BuiltFile, BuiltObject, CollectionEdit, CollectionItem, bookmark_filename_of,
    bookmark_needs_body, bookmark_object, bookmark_target_of, merge_bookmark, merge_tag,
    post_version_of, post_versions_dir, tag_object,
};
use crate::secret::SecretValue;
use crate::signin::{Outcome, SignInEvent, SignIns};

/// Page size when the caller gives none, and the largest one accepted.
const DEFAULT_LIST_LIMIT: u16 = 100;
const MAX_LIST_LIMIT: u16 = 1000;

/// The signed-in user's session as the app shows it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SessionInfo {
    /// The user's public key, z-base-32 (52 characters).
    pub public_key: String,
    /// The user's homeserver key, z-base-32.
    pub homeserver: Option<String>,
    /// The client id the grant was issued to.
    pub client_id: Option<String>,
    /// Granted capabilities as `scope:actions`. Ring may grant fewer than requested.
    pub capabilities: Vec<String>,
    /// Grant id, as listed in Ring's session list.
    pub grant_id: Option<String>,
    /// When the grant expires, Unix seconds.
    pub grant_expires_at: Option<u64>,
}

/// A finished sign-in: store `secret` (Android Keystore-backed) to restore the session later.
#[derive(Debug, uniffi::Record)]
pub struct SignedIn {
    pub info: SessionInfo,
    /// The grant credential. A bearer secret: never log it.
    pub secret: Arc<SecretValue>,
}

/// Listing options. `cursor` is the `next_cursor` of the previous page.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ListOptions {
    #[uniffi(default = None)]
    pub cursor: Option<String>,
    /// Page size, 1 to 1000. 0 means the default of 100.
    #[uniffi(default = 100)]
    pub limit: u16,
    /// Reverse path order (newest first for timestamp-named files).
    #[uniffi(default = false)]
    pub reverse: bool,
    /// List only direct children; folders end with `/`.
    #[uniffi(default = false)]
    pub shallow: bool,
}

impl Default for ListOptions {
    fn default() -> Self {
        Self {
            cursor: None,
            limit: DEFAULT_LIST_LIMIT,
            reverse: false,
            shallow: false,
        }
    }
}

/// One listed file.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ListEntry {
    /// `pubky://<owner>/<path>`.
    pub uri: String,
    /// The absolute path, e.g. `/pub/social/v1/tags/X.json`.
    pub path: String,
}

/// One page of a listing. `next_cursor` is `None` on the last page.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ListPage {
    pub entries: Vec<ListEntry>,
    pub next_cursor: Option<String>,
}

/// A photo post and the file it attaches.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PhotoPost {
    pub file: BuiltFile,
    pub post: BuiltObject,
}

/// A saved place.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SavedPlace {
    pub place: String,
    pub path: String,
}

/// One page of saved places.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SavedPlacePage {
    pub places: Vec<SavedPlace>,
    pub next_cursor: Option<String>,
}

struct Inner {
    pubky: Pubky,
    session: Mutex<Option<PubkySession>>,
    /// Bumped by `forget_session`, so a failed sign-out never brings a forgotten session back.
    forgotten: AtomicU64,
    sign_ins: Arc<SignIns>,
}

pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Aborts a spawned task when the caller stops waiting for it (a cancelled coroutine).
struct AbortOnDrop(tokio::task::AbortHandle);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// The Pubky Maps core. Build one per process from a [`CoreConfig`] and keep it.
///
/// All network calls are `suspend` functions in Kotlin. They run on a Tokio runtime owned
/// by this object, so any coroutine dispatcher can call them; cancelling the coroutine
/// cancels the work.
#[derive(uniffi::Object)]
pub struct PubkyMapsCore {
    runtime: Option<Runtime>,
    handle: Handle,
    inner: Arc<Inner>,
}

impl Drop for PubkyMapsCore {
    fn drop(&mut self) {
        self.inner.sign_ins.cancel();
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
    }
}

impl Inner {
    fn session(&self) -> Result<PubkySession, CoreError> {
        lock(&self.session).clone().ok_or(CoreError::NotSignedIn)
    }

    /// Hands a sign-in result out: only now does its session become the core's session.
    fn hand_out(&self, outcome: Outcome) -> SignInEvent {
        match outcome {
            Outcome::Waiting => SignInEvent::Waiting,
            Outcome::RelayChanged(request) => SignInEvent::RelayChanged { request },
            Outcome::Finished(done) => {
                let done = *done;
                *lock(&self.session) = Some(done.session);
                SignInEvent::SignedIn {
                    result: done.result,
                }
            }
        }
    }
}

async fn session_info(session: &PubkySession) -> SessionInfo {
    let info = session.info();
    let mut out = SessionInfo {
        public_key: info.public_key().z32(),
        homeserver: None,
        client_id: None,
        capabilities: info
            .capabilities()
            .iter()
            .map(ToString::to_string)
            .collect(),
        grant_id: None,
        grant_expires_at: None,
    };
    if let Some(grant) = session.as_grant() {
        let grant_info = grant.session_info().await;
        out.homeserver = Some(grant_info.homeserver.z32());
        out.client_id = Some(grant_info.client_id.to_string());
        out.capabilities = grant_info
            .capabilities
            .iter()
            .map(ToString::to_string)
            .collect();
        out.grant_id = Some(grant_info.grant_id.to_string());
        out.grant_expires_at = Some(grant_info.grant_expires_at);
    }
    out
}

pub(crate) async fn signed_in(session: &PubkySession) -> Result<SignedIn, CoreError> {
    let grant = session
        .as_grant()
        .ok_or_else(|| CoreError::internal("sign-in did not produce a grant session"))?;
    let secret = grant
        .export_local_secret()
        .await
        .ok_or_else(|| CoreError::internal("the session key cannot be exported"))?;
    Ok(SignedIn {
        info: session_info(session).await,
        secret: SecretValue::wrap(secret),
    })
}

/// A path in the signed-in user's own storage.
fn own_path(path: &str) -> Result<String, CoreError> {
    let path = path.trim();
    let path = if path.starts_with('/') {
        path.to_string()
    } else {
        format!("/{path}")
    };
    if !(path.starts_with("/pub/") || path.starts_with("/priv/")) {
        return Err(CoreError::validation(format!(
            "paths start with /pub/ or /priv/: {path}"
        )));
    }
    Ok(path)
}

/// A public `pubky://` address of another user's data.
fn public_address(uri: &str) -> Result<PubkyResource, CoreError> {
    let resource: PubkyResource = uri.trim().parse()?;
    if !resource.path.as_str().starts_with("/pub/") {
        return Err(CoreError::validation(format!(
            "only /pub/ data of other users is readable: {uri}"
        )));
    }
    Ok(resource)
}

fn body_error(error: impl std::fmt::Display) -> CoreError {
    CoreError::Network {
        reason: redact(&format!("reading the response failed: {error}")),
    }
}

async fn read_body(response: reqwest::Response) -> Result<Vec<u8>, CoreError> {
    Ok(response.bytes().await.map_err(body_error)?.to_vec())
}

/// The bytes at `path`, or `None` when nothing is stored there.
async fn get_optional(storage: &SessionStorage, path: &str) -> Result<Option<Vec<u8>>, CoreError> {
    match storage.get(path).await {
        Ok(response) => read_body(response).await.map(Some),
        Err(error) => match CoreError::from(error) {
            CoreError::NotFound { .. } => Ok(None),
            other => Err(other),
        },
    }
}

/// A DELETE that treats "already gone" as done.
async fn delete_idempotent(storage: &SessionStorage, path: &str) -> Result<(), CoreError> {
    match storage.delete(path).await {
        Ok(_) => Ok(()),
        Err(error) => match CoreError::from(error) {
            CoreError::NotFound { .. } => Ok(()),
            other => Err(other),
        },
    }
}

fn page_limit(options: &ListOptions) -> Result<u16, CoreError> {
    match options.limit {
        0 => Ok(DEFAULT_LIST_LIMIT),
        n if n <= MAX_LIST_LIMIT => Ok(n),
        _ => Err(CoreError::validation(format!(
            "page size must be at most {MAX_LIST_LIMIT}"
        ))),
    }
}

/// A page with a cursor after its last entry. Any non-empty page gets one, because the
/// homeserver may return fewer entries than asked for; the end is an empty page.
fn to_page(entries: Vec<PubkyResource>) -> ListPage {
    let next_cursor = entries.last().map(PubkyResource::to_pubky_url);
    ListPage {
        entries: entries
            .into_iter()
            .map(|entry| ListEntry {
                uri: entry.to_pubky_url(),
                path: entry.path.as_str().to_string(),
            })
            .collect(),
        next_cursor,
    }
}

async fn list_own(
    storage: &SessionStorage,
    dir: &str,
    options: &ListOptions,
) -> Result<ListPage, CoreError> {
    let limit = page_limit(options)?;
    let mut builder = storage
        .list(dir)?
        .limit(limit)
        .reverse(options.reverse)
        .shallow(options.shallow);
    if let Some(cursor) = &options.cursor {
        builder = builder.cursor(cursor);
    }
    Ok(to_page(builder.send().await?))
}

impl PubkyMapsCore {
    /// Runs `work` on the core's runtime and waits for it; dropping the wait aborts it.
    async fn run<T, F>(&self, work: F) -> Result<T, CoreError>
    where
        T: Send + 'static,
        F: Future<Output = Result<T, CoreError>> + Send + 'static,
    {
        let task = self.handle.spawn(work);
        let _abort = AbortOnDrop(task.abort_handle());
        match task.await {
            Ok(result) => result,
            Err(error) if error.is_cancelled() => Err(CoreError::Cancelled),
            Err(_) => Err(CoreError::internal("a core task panicked")),
        }
    }
}

#[uniffi::export]
impl PubkyMapsCore {
    /// Builds the core: its runtime, the HTTP client and the Pkarr resolver (DHT and relays
    /// as configured). No network call happens here.
    #[uniffi::constructor]
    pub fn new(config: CoreConfig) -> Result<Arc<Self>, CoreError> {
        let config = config.validate()?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("pubky-maps-core")
            .enable_all()
            .build()
            .map_err(|e| CoreError::internal(format!("runtime: {e}")))?;
        let handle = runtime.handle().clone();
        let client = {
            let _context = handle.enter();
            build_client(&config)?
        };
        let inner = Arc::new(Inner {
            pubky: Pubky::with_client(client.clone()),
            session: Mutex::new(None),
            forgotten: AtomicU64::new(0),
            sign_ins: Arc::new(SignIns::new(client, config.auth_relay_urls)?),
        });
        Ok(Arc::new(Self {
            runtime: Some(runtime),
            handle,
            inner,
        }))
    }

    // --- Sign-in with Pubky Ring ----------------------------------------------------------

    /// Starts a sign-in on the first auth relay. Open `authorization_url()` (Ring handles
    /// `pubkyauth://`) or show it as a QR code, then `await_sign_in()`. A running sign-in is
    /// replaced; a result of an earlier one that was never collected is revoked.
    pub fn start_sign_in(&self) -> Result<Arc<AuthRequest>, CoreError> {
        let _context = self.handle.enter();
        self.inner.sign_ins.start(FlowSpec::SignIn)
    }

    /// Starts a sign-up: Ring creates the account on `homeserver` (z-base-32 key), with an
    /// optional signup token, then signs in.
    pub fn start_sign_up(
        &self,
        homeserver: String,
        signup_token: Option<String>,
    ) -> Result<Arc<AuthRequest>, CoreError> {
        let homeserver = PublicKey::try_from(homeserver.trim())
            .map_err(|e| CoreError::validation(format!("homeserver key: {e}")))?
            .z32();
        let signup_token = signup_token
            .map(|t| t.trim().to_string())
            .filter(|t| !t.is_empty());
        let _context = self.handle.enter();
        self.inner.sign_ins.start(FlowSpec::SignUp {
            homeserver,
            signup_token,
        })
    }

    /// Picks a sign-in up again after the process was killed, from the last stored
    /// `AuthRequest.pending_state()`. Useful while the relay still holds the approval (about
    /// 5 minutes after Ring sent it). Resuming the running sign-in, or one whose result is
    /// waiting to be collected, changes nothing.
    pub fn resume_sign_in(&self, state: Arc<SecretValue>) -> Result<Arc<AuthRequest>, CoreError> {
        let _context = self.handle.enter();
        self.inner.sign_ins.resume(state)
    }

    /// The running sign-in's request (after a relay change: the new one), if any.
    pub fn current_sign_in(&self) -> Option<Arc<AuthRequest>> {
        self.inner.sign_ins.current()
    }

    /// Whether a sign-in is running or finished without anyone collecting it yet.
    pub fn has_pending_sign_in(&self) -> bool {
        self.inner.sign_ins.has_pending()
    }

    /// Waits until Ring approves (`SignedIn`) or the sign-in moves to the next auth relay
    /// (`RelayChanged`: show the new link and call this again). Never returns `Waiting`.
    ///
    /// Errors: `Cancelled` after `cancel_sign_in` or when another sign-in was started;
    /// `Auth` once the link is 10 minutes old; `Network` "the sign-in relay is unreachable"
    /// when every configured relay failed. A relay is left after 4 failed listens in a row
    /// (about 14 s with back-off). Call it from one place at a time.
    ///
    /// Cancelling the calling coroutine is safe: an approval that already arrived is still
    /// exchanged, parked, and handed out by the next `await_sign_in`/`poll_sign_in`; the
    /// session is installed only then. One narrow case remains: the coroutine is cancelled
    /// in the moment between the hand-out and Kotlin receiving the value. The session is
    /// installed but the app never saw `SignedIn.secret`; `current_session_secret()` gives it
    /// again, so an app that finds `current_session()` set without a stored secret stores
    /// that. Android may block the network of a backgrounded app, so wait only while the app
    /// is on screen.
    pub async fn await_sign_in(&self) -> Result<SignInEvent, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let outcome = Arc::clone(&inner.sign_ins).wait().await?;
            Ok(inner.hand_out(outcome))
        })
        .await
    }

    /// One check without waiting: `Waiting`, `RelayChanged` or `SignedIn`, like
    /// `await_sign_in`. Safe to call while an `await_sign_in` runs.
    pub async fn poll_sign_in(&self) -> Result<SignInEvent, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let outcome = Arc::clone(&inner.sign_ins).poll_once().await?;
            Ok(inner.hand_out(outcome))
        })
        .await
    }

    /// Stops a running sign-in; a waiting `await_sign_in` ends with `Cancelled`. If the
    /// approval was already being exchanged, its result is parked for the next
    /// `await_sign_in`/`poll_sign_in` (or revoked by the next `start_sign_in`).
    pub fn cancel_sign_in(&self) {
        self.inner.sign_ins.cancel();
    }

    // --- Session ----------------------------------------------------------------------------

    /// Restores a session from the stored secret (needs the network: it mints a new token).
    /// `Auth` means the grant was revoked or expired: sign in again. `Network` means retry.
    pub async fn restore_session(
        &self,
        secret: Arc<SecretValue>,
    ) -> Result<SessionInfo, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner
                .pubky
                .restore_session(secret.as_str())
                .await
                .map_err(|error| match CoreError::from(error) {
                    error @ (CoreError::Network { .. }
                    | CoreError::RateLimited { .. }
                    | CoreError::Server { .. }) => error,
                    CoreError::Auth { reason } | CoreError::Validation { reason } => {
                        CoreError::auth(format!("stored session cannot be restored: {reason}"))
                    }
                    other => other,
                })?;
            let info = session_info(&session).await;
            *lock(&inner.session) = Some(session);
            Ok(info)
        })
        .await
    }

    /// The signed-in session, if any. No network call.
    pub async fn current_session(&self) -> Option<SessionInfo> {
        let session = lock(&self.inner.session).clone()?;
        self.run(async move { Ok(session_info(&session).await) })
            .await
            .ok()
    }

    /// The secret of the current session, as `SignedIn.secret` gave it: for an app that has a
    /// session but no stored secret (see `await_sign_in`). `None` when not signed in.
    pub async fn current_session_secret(&self) -> Option<Arc<SecretValue>> {
        let session = lock(&self.inner.session).clone()?;
        self.run(async move {
            let secret = match session.as_grant() {
                Some(grant) => grant.export_local_secret().await,
                None => None,
            };
            Ok(secret.map(SecretValue::wrap))
        })
        .await
        .ok()
        .flatten()
    }

    /// Asks the homeserver whether the session is still valid. `None` (and the session is
    /// dropped) when it expired or was revoked.
    pub async fn revalidate_session(&self) -> Result<Option<SessionInfo>, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            if session.revalidate().await?.is_none() {
                lock(&inner.session).take();
                return Ok(None);
            }
            Ok(Some(session_info(&session).await))
        })
        .await
    }

    /// Signs out and revokes this app's grant on the homeserver. When the revoke fails (for
    /// example offline) the session stays and the error is returned; `forget_session` then
    /// drops it locally.
    pub async fn sign_out(&self) -> Result<(), CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let Some(session) = lock(&inner.session).take() else {
                return Ok(());
            };
            let generation = inner.forgotten.load(Ordering::SeqCst);
            // Not aborted with the caller: a half-done sign-out either revokes the grant or
            // puts the session back.
            tokio::spawn(async move {
                match session.signout().await {
                    Ok(()) => Ok(()),
                    Err((error, session)) => {
                        if inner.forgotten.load(Ordering::SeqCst) == generation {
                            lock(&inner.session).get_or_insert(session);
                        }
                        Err(error.into())
                    }
                }
            })
            .await
            .unwrap_or_else(|_| Err(CoreError::internal("the sign-out task panicked")))
        })
        .await
    }

    /// Drops the session locally without contacting the homeserver.
    pub fn forget_session(&self) {
        self.inner.forgotten.fetch_add(1, Ordering::SeqCst);
        lock(&self.inner.session).take();
    }

    // --- Own storage ------------------------------------------------------------------------

    /// Writes `body` at `path` (`/pub/...` or `/priv/...`) on the user's homeserver.
    pub async fn put(&self, path: String, body: Vec<u8>) -> Result<(), CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let path = own_path(&path)?;
            inner.session()?.storage().put(path.as_str(), body).await?;
            Ok(())
        })
        .await
    }

    /// Reads the file at `path`. `NotFound` when there is none.
    pub async fn get(&self, path: String) -> Result<Vec<u8>, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let path = own_path(&path)?;
            let response = inner.session()?.storage().get(path.as_str()).await?;
            read_body(response).await
        })
        .await
    }

    /// Whether a file exists at `path`.
    pub async fn exists(&self, path: String) -> Result<bool, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let path = own_path(&path)?;
            Ok(inner.session()?.storage().exists(path.as_str()).await?)
        })
        .await
    }

    /// Deletes the file at `path`; deleting a missing file succeeds.
    pub async fn delete(&self, path: String) -> Result<(), CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let path = own_path(&path)?;
            delete_idempotent(&inner.session()?.storage(), &path).await
        })
        .await
    }

    /// Lists a folder (`dir` ends with `/`), one page at a time.
    pub async fn list(&self, dir: String, options: ListOptions) -> Result<ListPage, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let dir = own_path(&dir)?;
            list_own(&inner.session()?.storage(), &dir, &options).await
        })
        .await
    }

    // --- Other users' public data (no session) ------------------------------------------

    /// Reads a public file of any user: `pubky://<key>/pub/...`.
    pub async fn public_get(&self, uri: String) -> Result<Vec<u8>, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let address = public_address(&uri)?;
            let response = inner.pubky.public_storage().get(address).await?;
            read_body(response).await
        })
        .await
    }

    /// Lists a public folder of any user: `pubky://<key>/pub/.../`.
    pub async fn public_list(
        &self,
        uri: String,
        options: ListOptions,
    ) -> Result<ListPage, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let address = public_address(&uri)?;
            let limit = page_limit(&options)?;
            let storage = inner.pubky.public_storage();
            let mut builder = storage
                .list(address)?
                .limit(limit)
                .reverse(options.reverse)
                .shallow(options.shallow);
            if let Some(cursor) = &options.cursor {
                builder = builder.cursor(cursor);
            }
            Ok(to_page(builder.send().await?))
        })
        .await
    }

    // --- Social objects about places ------------------------------------------------------

    /// Tags a place. Reads the tag's address first and keeps unknown members of a stored
    /// copy; writes nothing when the same tag is already there.
    pub async fn tag_place(&self, place: String, label: String) -> Result<BuiltObject, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let author = session.public_key().z32();
            let (tag, id) = tag_object(&place, &label)?;
            let path = PubkySocialTag::create_path(&id);
            let storage = session.storage();
            let existing = get_optional(&storage, &path).await?;
            let (built, write) = merge_tag(&author, tag, id, existing.as_deref())?;
            if write {
                storage.put(built.path.as_str(), built.json.clone()).await?;
            }
            Ok(built)
        })
        .await
    }

    /// Removes this user's tag `label` from a place.
    pub async fn untag_place(&self, place: String, label: String) -> Result<(), CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let (_, id) = tag_object(&place, &label)?;
            delete_idempotent(&session.storage(), &PubkySocialTag::create_path(&id)).await
        })
        .await
    }

    /// Posts a review: a `note` whose parent is the place.
    pub async fn post_review(&self, place: String, text: String) -> Result<BuiltObject, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let built = objects::build_review(session.public_key().z32(), place, text)?;
            session
                .storage()
                .put(built.path.as_str(), built.json.clone())
                .await?;
            Ok(built)
        })
        .await
    }

    /// Uploads a photo (JPEG, PNG or WebP; EXIF already stripped) and posts it as an
    /// `image` post embedding the place. The file goes first; an identical file already
    /// uploaded is not sent again.
    pub async fn post_photo(
        &self,
        place: String,
        image: Vec<u8>,
        mime_type: String,
        alt: Option<String>,
        caption: Option<String>,
    ) -> Result<PhotoPost, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let author = session.public_key().z32();
            let (file, bytes) = objects::photo_file(&author, image, &mime_type)?;
            let post = objects::build_photo_post(author, place, file.uri.clone(), alt, caption)?;
            let storage = session.storage();
            if !storage.exists(file.path.as_str()).await? {
                storage.put(file.path.as_str(), bytes).await?;
            }
            storage.put(post.path.as_str(), post.json.clone()).await?;
            Ok(PhotoPost { file, post })
        })
        .await
    }

    /// Creates a public list (at most 100 places).
    pub async fn create_collection(
        &self,
        name: String,
        description: Option<String>,
        items: Vec<CollectionItem>,
    ) -> Result<BuiltObject, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let built =
                objects::build_collection(session.public_key().z32(), name, description, items)?;
            session
                .storage()
                .put(built.path.as_str(), built.json.clone())
                .await?;
            Ok(built)
        })
        .await
    }

    /// Adds a place to, or removes one from, a list: reads the newest version from the
    /// homeserver, applies the edit, writes a new version. Returns the newest version
    /// (unchanged when the edit was a no-op).
    pub async fn edit_collection(
        &self,
        post_id: String,
        edit: CollectionEdit,
    ) -> Result<BuiltObject, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let author = session.public_key().z32();
            let storage = session.storage();
            let dir = post_versions_dir(&post_id)?;
            let options = ListOptions {
                reverse: true,
                ..ListOptions::default()
            };
            let page = list_own(&storage, &dir, &options).await?;
            let (head_edit, head_path) = page
                .entries
                .iter()
                .find_map(|entry| {
                    post_version_of(&entry.uri)
                        .filter(|(id, _)| *id == post_id)
                        .map(|(_, edit)| (edit, entry.path.clone()))
                })
                .ok_or_else(|| CoreError::NotFound {
                    reason: format!("list {post_id}"),
                })?;
            let head_json =
                String::from_utf8(read_body(storage.get(head_path.as_str()).await?).await?)
                    .map_err(|_| CoreError::validation("stored list is not UTF-8"))?;
            match objects::edit_collection(
                author.clone(),
                post_id.clone(),
                head_edit.clone(),
                head_json.clone(),
                edit,
            )? {
                Some(built) => {
                    storage.put(built.path.as_str(), built.json.clone()).await?;
                    Ok(built)
                }
                None => Ok(BuiltObject {
                    uri: post_uri_builder(author, post_id.clone()),
                    id: post_id,
                    edit_id: Some(head_edit),
                    path: head_path,
                    json: head_json,
                }),
            }
        })
        .await
    }

    /// Saves a place privately (`/priv/social/v1/bookmarks/`; `/priv` is alpha).
    pub async fn bookmark_place(&self, place: String) -> Result<BuiltObject, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let author = session.public_key().z32();
            let created = bookmark_object(&place)?;
            let storage = session.storage();
            let existing = get_optional(&storage, &created.path).await?;
            let (built, write) = merge_bookmark(&author, created, existing.as_deref())?;
            if write {
                storage.put(built.path.as_str(), built.json.clone()).await?;
            }
            Ok(built)
        })
        .await
    }

    /// Removes a saved place.
    pub async fn remove_bookmark(&self, place: String) -> Result<(), CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let created = bookmark_object(&place)?;
            delete_idempotent(&session.storage(), &created.path).await
        })
        .await
    }

    /// Lists saved places. Normal-length places cost no extra request; entries that are not
    /// valid bookmarks are skipped.
    pub async fn list_bookmarks(&self, options: ListOptions) -> Result<SavedPlacePage, CoreError> {
        let inner = Arc::clone(&self.inner);
        self.run(async move {
            let session = inner.session()?;
            let storage = session.storage();
            let dir = social_path(Root::Priv, PubkySocialBookmark::PATH_SEGMENT);
            let page = list_own(&storage, &dir, &options).await?;
            let mut places = Vec::with_capacity(page.entries.len());
            for entry in page.entries {
                let Some(filename) = bookmark_filename_of(&entry.path) else {
                    continue;
                };
                let body = if bookmark_needs_body(filename) {
                    match get_optional(&storage, &entry.path).await? {
                        Some(body) => Some(body),
                        None => continue,
                    }
                } else {
                    None
                };
                if let Ok(place) = bookmark_target_of(filename, body.as_deref()) {
                    places.push(SavedPlace {
                        place,
                        path: entry.path,
                    });
                }
            }
            Ok(SavedPlacePage {
                places,
                next_cursor: page.next_cursor,
            })
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::AuthFlowType;
    use crate::config::default_core_config;
    use std::time::Duration;

    /// Unreachable local ports: no unit test touches the network.
    const DEAD_RELAY: &str = "http://127.0.0.1:9/inbox";
    const DEAD_RELAY_2: &str = "http://127.0.0.1:7/inbox";

    fn core_with_relays(relays: &[&str]) -> Arc<PubkyMapsCore> {
        let mut config = default_core_config();
        config.dht_enabled = false;
        config.pkarr_relays = vec!["http://127.0.0.1:9".into()];
        config.auth_relay_urls = relays.iter().map(|r| (*r).to_string()).collect();
        PubkyMapsCore::new(config).unwrap()
    }

    fn offline_core() -> Arc<PubkyMapsCore> {
        core_with_relays(&[DEAD_RELAY])
    }

    fn relay_of(request: &AuthRequest) -> Option<String> {
        url::Url::parse(&request.authorization_url())
            .unwrap()
            .query_pairs()
            .find_map(|(k, v)| (k == "relay").then(|| v.into_owned()))
    }

    #[test]
    fn paths_must_be_under_pub_or_priv() {
        assert_eq!(own_path("/pub/a/b").unwrap(), "/pub/a/b");
        assert_eq!(own_path("priv/a/").unwrap(), "/priv/a/");
        assert!(own_path("/etc/passwd").is_err());
        assert!(own_path("/public/x").is_err());
    }

    #[test]
    fn public_reads_stay_in_pub() {
        let key = "operrr8wsbpr3ue9d4qj41ge1kcc6r7fdiy6o3ugjrrhi4y77rdo";
        assert!(public_address(&format!("pubky://{key}/pub/social/v1/tags/")).is_ok());
        assert!(public_address(&format!("pubky://{key}/priv/social/v1/bookmarks/")).is_err());
        assert!(public_address("https://example.com/pub/x").is_err());
    }

    #[test]
    fn list_limits() {
        assert_eq!(page_limit(&ListOptions::default()).unwrap(), 100);
        let zero = ListOptions {
            limit: 0,
            ..ListOptions::default()
        };
        assert_eq!(page_limit(&zero).unwrap(), 100);
        let big = ListOptions {
            limit: 1001,
            ..ListOptions::default()
        };
        assert!(page_limit(&big).is_err());
    }

    #[test]
    fn calls_without_a_session_fail_cleanly() {
        let core = offline_core();
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            assert!(matches!(
                core.put("/pub/x".into(), vec![1]).await,
                Err(CoreError::NotSignedIn)
            ));
            assert!(matches!(
                core.tag_place("geo:1,1".into(), "x".into()).await,
                Err(CoreError::NotSignedIn)
            ));
            assert!(core.current_session().await.is_none());
            assert!(core.sign_out().await.is_ok());
            assert!(matches!(
                core.poll_sign_in().await,
                Err(CoreError::Auth { .. })
            ));
        });
    }

    #[test]
    fn sign_in_flow_starts_offline_and_cancels() {
        let core = offline_core();
        let request = core.start_sign_in().unwrap();
        let url = request.authorization_url();
        assert!(url.starts_with("pubkyauth://signin_grant?"), "{url}");
        assert!(url.contains("cid=pubky.maps"), "{url}");
        assert_eq!(relay_of(&request).as_deref(), Some(DEAD_RELAY), "{url}");
        assert!(url.contains("pubkymaps"), "{url}");
        assert!(
            url.contains("%2Fpub%2Fsocial%2Fv1%2Ftags%2F") || url.contains("/pub/social/v1/tags/"),
            "{url}"
        );
        assert!(core.has_pending_sign_in());
        assert_eq!(request.flow_type(), AuthFlowType::SignIn);

        // The saved state resumes the same flow.
        let resumed = core.resume_sign_in(request.pending_state()).unwrap();
        assert_eq!(resumed.authorization_url(), url);
        assert_eq!(resumed.callback_nonce(), request.callback_nonce());
        assert_eq!(
            core.current_sign_in().map(|r| r.authorization_url()),
            Some(url.clone())
        );

        core.cancel_sign_in();
        assert!(!core.has_pending_sign_in());
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            assert!(matches!(
                core.await_sign_in().await,
                Err(CoreError::Auth { .. })
            ));
        });
    }

    #[test]
    fn unreachable_relays_switch_then_fail_as_network() {
        let core = core_with_relays(&[DEAD_RELAY, DEAD_RELAY_2]);
        let first = core.start_sign_in().unwrap();
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let event = tokio::time::timeout(Duration::from_secs(30), core.await_sign_in())
                .await
                .expect("the first relay is given up in time")
                .unwrap();
            let SignInEvent::RelayChanged { request } = event else {
                panic!("expected a relay change, got {event:?}");
            };
            assert_eq!(relay_of(&request).as_deref(), Some(DEAD_RELAY_2));
            assert_ne!(request.authorization_url(), first.authorization_url());
            assert_ne!(request.callback_nonce(), first.callback_nonce());
            assert_eq!(
                core.current_sign_in().map(|r| r.authorization_url()),
                Some(request.authorization_url())
            );
            // The new state resumes the new link.
            let resumed = core.resume_sign_in(request.pending_state()).unwrap();
            assert_eq!(resumed.authorization_url(), request.authorization_url());

            let err = tokio::time::timeout(Duration::from_secs(30), core.await_sign_in())
                .await
                .expect("the last relay is given up in time")
                .unwrap_err();
            assert!(
                matches!(&err, CoreError::Network { reason } if reason.contains("unreachable")),
                "{err:?}"
            );
        });
        assert!(!core.has_pending_sign_in());
        assert!(core.current_sign_in().is_none());
    }

    /// A relay that accepts connections and never answers (dropped packets, a stalled
    /// proxy). Returns its inbox URL.
    async fn black_hole_relay() -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let mut held = Vec::new();
            while let Ok((socket, _)) = listener.accept().await {
                held.push(socket);
            }
        });
        format!("http://{address}/inbox")
    }

    /// A relay host that answers every request with 403 (a block page). Returns its inbox URL.
    async fn forbidden_relay() -> String {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((mut socket, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let mut buf = [0_u8; 4096];
                    let _ = socket.read(&mut buf).await;
                    let _ = socket
                        .write_all(
                            b"HTTP/1.1 403 Forbidden\r\ncontent-type: text/html\r\ncontent-length: 21\r\nconnection: close\r\n\r\n<h1>Blocked here</h1>",
                        )
                        .await;
                });
            }
        });
        format!("http://{address}/inbox")
    }

    /// Waits for the sign-in to leave its first relay; returns the new relay and the time.
    async fn wait_for_switch(core: &Arc<PubkyMapsCore>) -> (Option<String>, Duration) {
        let started = std::time::Instant::now();
        let event = tokio::time::timeout(Duration::from_secs(60), core.await_sign_in())
            .await
            .expect("the bad relay is given up in time")
            .unwrap();
        let SignInEvent::RelayChanged { request } = event else {
            panic!("expected a relay change, got {event:?}");
        };
        (relay_of(&request), started.elapsed())
    }

    #[test]
    fn a_relay_that_never_answers_is_left_for_the_next() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let silent = black_hole_relay().await;
            let mut config = default_core_config();
            config.dht_enabled = false;
            config.pkarr_relays = vec!["http://127.0.0.1:9".into()];
            config.auth_relay_urls = vec![silent, DEAD_RELAY_2.into()];
            // The SDK's own relay requests time out and are retried forever: only the probe
            // notices this relay.
            config.request_timeout_ms = 2_000;
            let core = PubkyMapsCore::new(config).unwrap();
            core.start_sign_in().unwrap();
            let (relay, took) = wait_for_switch(&core).await;
            assert_eq!(relay.as_deref(), Some(DEAD_RELAY_2));
            assert!(took < Duration::from_secs(30), "{took:?}");
        });
    }

    #[test]
    fn a_relay_that_answers_403_is_left_for_the_next() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let blocked = forbidden_relay().await;
            let core = core_with_relays(&[blocked.as_str(), DEAD_RELAY_2]);
            core.start_sign_in().unwrap();
            let (relay, took) = wait_for_switch(&core).await;
            assert_eq!(relay.as_deref(), Some(DEAD_RELAY_2));
            assert!(took < Duration::from_secs(30), "{took:?}");
        });
    }

    #[test]
    fn no_session_secret_without_a_session() {
        let core = offline_core();
        let runtime = tokio::runtime::Runtime::new().unwrap();
        assert!(runtime.block_on(core.current_session_secret()).is_none());
    }

    #[test]
    fn polling_reports_the_relay_change_too() {
        let core = core_with_relays(&[DEAD_RELAY, DEAD_RELAY_2]);
        core.start_sign_in().unwrap();
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let mut events = Vec::new();
            let end = loop {
                match core.poll_sign_in().await {
                    Ok(SignInEvent::Waiting) => {}
                    Ok(SignInEvent::RelayChanged { request }) => {
                        events.push(relay_of(&request));
                    }
                    Ok(SignInEvent::SignedIn { .. }) => panic!("no relay works"),
                    Err(err) => break err,
                }
                assert!(events.len() <= 1, "{events:?}");
                tokio::time::sleep(Duration::from_millis(20)).await;
            };
            assert_eq!(events, vec![Some(DEAD_RELAY_2.to_string())]);
            assert!(matches!(end, CoreError::Network { .. }), "{end:?}");
        });
    }

    #[test]
    fn cancel_wins_over_relay_retries() {
        // The relay refuses connections, so the wait sits in its retry loop when the user
        // cancels; the restart must not bring the cancelled flow back.
        let core = core_with_relays(&[DEAD_RELAY, DEAD_RELAY_2]);
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            core.start_sign_in().unwrap();
            let waiting = {
                let core = Arc::clone(&core);
                tokio::spawn(async move { core.await_sign_in().await })
            };
            tokio::time::sleep(Duration::from_millis(150)).await;
            core.cancel_sign_in();
            let outcome = tokio::time::timeout(Duration::from_secs(10), waiting)
                .await
                .expect("the wait ends after cancel")
                .unwrap();
            assert!(matches!(outcome, Err(CoreError::Cancelled)), "{outcome:?}");
            assert!(!core.has_pending_sign_in());
        });
    }

    #[test]
    fn sign_up_needs_a_homeserver_key() {
        let core = offline_core();
        assert!(matches!(
            core.start_sign_up("nope".into(), None),
            Err(CoreError::Validation { .. })
        ));
        let request = core
            .start_sign_up(
                "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo".into(),
                Some("AAAA-BBBB-CCCC".into()),
            )
            .unwrap();
        assert!(
            request
                .authorization_url()
                .starts_with("pubkyauth://signup_grant?")
        );
        assert_eq!(request.flow_type(), AuthFlowType::SignUp);
    }

    #[test]
    fn restore_rejects_garbage_without_echoing_it() {
        let core = offline_core();
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let err = runtime
            .block_on(
                core.restore_session(SecretValue::new("pubky-grant-credential-v1.garbage".into())),
            )
            .unwrap_err();
        assert!(!err.to_string().contains("garbage"), "{err}");
    }

    mod exchange {
        //! A sign-in whose exchange is held by the test gate: what happens to the result
        //! when the caller goes away, the user cancels, or starts another sign-in meanwhile.
        //! Needs Postgres like the testnet integration tests.

        use super::*;
        use crate::signin::ExchangeGate;
        use pubky_testnet::EphemeralTestnet;
        use pubky_testnet::pubky::{Keypair, PubkySigner};

        async fn setup() -> (EphemeralTestnet, PubkySigner) {
            let testnet = EphemeralTestnet::builder()
                .with_http_relay()
                .build()
                .await
                .expect("testnet starts (is Postgres reachable?)");
            let ring = testnet.sdk().unwrap().signer(Keypair::random());
            ring.signup(&testnet.homeserver_app().public_key(), None)
                .await
                .unwrap();
            (testnet, ring)
        }

        fn gated_core(testnet: &EphemeralTestnet) -> (Arc<PubkyMapsCore>, Arc<ExchangeGate>) {
            let mut config = default_core_config();
            config.pkarr_relays = Vec::new();
            config.dht_bootstrap_nodes = testnet
                .testnet
                .dht_bootstrap_nodes()
                .iter()
                .map(ToString::to_string)
                .collect();
            config.auth_relay_urls = vec![format!("{}inbox", testnet.http_relay().local_url())];
            config.request_timeout_ms = 10_000;
            let core = PubkyMapsCore::new(config).unwrap();
            let gate = Arc::new(ExchangeGate::default());
            core.inner.sign_ins.set_gate(Some(Arc::clone(&gate)));
            (core, gate)
        }

        /// Waits until the core revoked a result, then checks its grant no longer works.
        async fn assert_revoked(testnet: &EphemeralTestnet, core: &Arc<PubkyMapsCore>) {
            let secret = tokio::time::timeout(Duration::from_secs(20), async {
                loop {
                    if let Some(secret) = core.inner.sign_ins.revoked_secrets().pop() {
                        return secret;
                    }
                    tokio::time::sleep(Duration::from_millis(50)).await;
                }
            })
            .await
            .expect("the result is revoked");
            let other = gated_core(testnet).0;
            let err = other.restore_session(secret).await.unwrap_err();
            assert!(matches!(err, CoreError::Auth { .. }), "{err:?}");
        }

        /// Starts a wait, lets Ring approve, and returns once the exchange is under way.
        async fn approved_and_exchanging(
            core: &Arc<PubkyMapsCore>,
            gate: &ExchangeGate,
            ring: &PubkySigner,
        ) -> tokio::task::JoinHandle<Result<SignInEvent, CoreError>> {
            let request = core.start_sign_in().unwrap();
            let waiting = {
                let core = Arc::clone(core);
                tokio::spawn(async move { core.await_sign_in().await })
            };
            ring.approve_auth(&request.authorization_url())
                .await
                .unwrap();
            tokio::time::timeout(Duration::from_secs(60), gate.reached.notified())
                .await
                .expect("the exchange starts");
            waiting
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
        #[pubky_testnet::test]
        #[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
        async fn cancelled_during_exchange_is_parked_not_installed() {
            let (testnet, ring) = setup().await;
            let (core, gate) = gated_core(&testnet);
            let waiting = approved_and_exchanging(&core, &gate, &ring).await;

            // The coroutine goes away and the user taps cancel while the exchange runs.
            waiting.abort();
            core.cancel_sign_in();
            gate.release.notify_one();
            tokio::time::timeout(Duration::from_secs(10), async {
                while !core.has_pending_sign_in() {
                    tokio::time::sleep(Duration::from_millis(20)).await;
                }
            })
            .await
            .expect("the result is parked");
            assert!(
                core.current_session().await.is_none(),
                "a parked result is not installed before it is handed out"
            );

            let event = core.poll_sign_in().await.unwrap();
            let SignInEvent::SignedIn { result } = event else {
                panic!("expected the parked result, got {event:?}");
            };
            assert_eq!(result.info.public_key, ring.public_key().z32());
            let session = core.current_session().await.unwrap();
            assert_eq!(
                session, result.info,
                "the core runs as the account the app received"
            );
            assert!(!core.has_pending_sign_in());

            // The secret the app got is the one that works, and the core gives it again.
            let again = core.current_session_secret().await.unwrap();
            assert_eq!(again.expose(), result.secret.expose());
            let other = gated_core(&testnet).0;
            let restored = other.restore_session(result.secret).await.unwrap();
            assert_eq!(restored.public_key, result.info.public_key);
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
        #[pubky_testnet::test]
        #[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
        async fn replaced_during_exchange_is_not_installed() {
            let (testnet, ring) = setup().await;
            let (core, gate) = gated_core(&testnet);
            let waiting = approved_and_exchanging(&core, &gate, &ring).await;

            // The user starts another sign-in while the first exchange runs.
            let second = core.start_sign_in().unwrap();
            gate.release.notify_one();
            let first = tokio::time::timeout(Duration::from_secs(10), waiting)
                .await
                .unwrap()
                .unwrap();
            assert!(matches!(first, Err(CoreError::Cancelled)), "{first:?}");
            assert!(core.current_session().await.is_none());
            assert!(matches!(
                core.poll_sign_in().await.unwrap(),
                SignInEvent::Waiting
            ));
            assert_eq!(
                core.current_sign_in().map(|r| r.authorization_url()),
                Some(second.authorization_url())
            );
            assert_revoked(&testnet, &core).await;
        }

        #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
        #[pubky_testnet::test]
        #[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
        async fn a_new_sign_in_revokes_an_uncollected_result() {
            let (testnet, ring) = setup().await;
            let (core, gate) = gated_core(&testnet);
            let waiting = approved_and_exchanging(&core, &gate, &ring).await;
            waiting.abort();
            core.cancel_sign_in();
            gate.release.notify_one();
            tokio::time::timeout(Duration::from_secs(10), async {
                while !core.has_pending_sign_in() {
                    tokio::time::sleep(Duration::from_millis(20)).await;
                }
            })
            .await
            .expect("the result is parked");

            core.start_sign_in().unwrap();
            assert!(core.current_session().await.is_none());
            assert!(matches!(
                core.poll_sign_in().await.unwrap(),
                SignInEvent::Waiting
            ));
            assert_revoked(&testnet, &core).await;
        }
    }
}
