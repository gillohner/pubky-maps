//! The Pubky Ring sign-in state machine.
//!
//! One sign-in attempt is a *chain*: it starts on the first auth relay, may restart its relay
//! listener (same link) after relay failures, and moves to the next relay (new link) when a
//! relay keeps failing. Its result is *parked* until an `await_sign_in`/`poll_sign_in` hands it
//! out; only then is the session installed in the core.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use pubky::{PubkyGrantAuthFlow, PubkyHttpClient, PubkySession};
use url::Url;

use crate::auth::{
    AuthRequest, FlowSpec, LoadedFlow, capabilities, client_id, flow_expired, load_flow, new_nonce,
    now_secs, save_flow, x_callback,
};
use crate::config::icann_client;
use crate::core::{SignedIn, lock, signed_in};
use crate::error::CoreError;
use crate::secret::SecretValue;

/// How often a waiting sign-in checks the relay listener (an in-memory check).
const POLL_INTERVAL: Duration = Duration::from_millis(250);
/// Back-off after the first relay failure; it doubles per failure, up to `MAX_BACKOFF`.
#[cfg(not(test))]
const BACKOFF_BASE: Duration = Duration::from_secs(2);
#[cfg(test)]
const BACKOFF_BASE: Duration = Duration::from_millis(100);
const MAX_BACKOFF: Duration = Duration::from_secs(8);
/// Relay failures in a row before the sign-in moves to the next relay. A failure is a
/// listener that gave up (3 failed requests inside the SDK) or a failed relay probe. With the
/// back-off that is about 14 s for a refused connection and about 1 minute for a relay that
/// never answers (each probe waits `PROBE_TIMEOUT`).
pub(crate) const RELAY_FAILURES_BEFORE_SWITCH: u32 = 4;
/// Deadline of one relay probe.
#[cfg(not(test))]
const PROBE_TIMEOUT: Duration = Duration::from_secs(10);
#[cfg(test)]
const PROBE_TIMEOUT: Duration = Duration::from_millis(500);
/// A waiting sign-in probes its relay when a listener starts and then this often. The SDK's
/// listener retries timeouts forever, so a relay that drops packets is only seen this way.
const PROBE_INTERVAL: Duration = Duration::from_secs(25);
/// What pubky's http-relay answers on `/inbox/{unknown id}/ack`.
const RELAY_NOT_FOUND_BODY: &str = "Not found";
/// A listener that ran this long before failing had a working relay: the count restarts.
const RELAY_HEALTHY_AFTER: Duration = Duration::from_secs(60);
/// Listener restarts allowed for errors that name no URL (relay or exchange, unknown).
const MAX_UNCLEAR_RESTARTS: u32 = 2;

/// What `await_sign_in` and `poll_sign_in` report.
#[derive(Debug, uniffi::Enum)]
pub enum SignInEvent {
    /// Still waiting for the approval (only `poll_sign_in` returns this).
    Waiting,
    /// The relay could not be reached, so the sign-in moved to the next auth relay with a
    /// new link. Show `request.authorization_url()` (QR code, intent) instead of the old one,
    /// store `request.pending_state()` in place of the old state, and wait again.
    RelayChanged { request: Arc<AuthRequest> },
    /// Signed in; the session is now the core's session.
    SignedIn { result: SignedIn },
}

/// A sign-in result nobody collected yet. Its session is not installed until it is handed out.
pub(crate) struct Finished {
    chain: u64,
    pub session: PubkySession,
    pub result: SignedIn,
}

/// One relay listener of a chain.
struct Flow {
    chain: u64,
    flow: PubkyGrantAuthFlow,
    request: Arc<AuthRequest>,
    spec: FlowSpec,
    relay_index: usize,
    relay_base: Option<String>,
    started_at: u64,
    relay_failures: u32,
    unclear_restarts: u32,
    listening_since: Instant,
    /// When the relay last answered a probe like a relay; `None` before the first probe.
    probed_at: Mutex<Option<Instant>>,
    /// One poll at a time: the SDK refuses concurrent polls of a flow.
    poll_turn: tokio::sync::Mutex<()>,
}

impl Flow {
    fn new(
        chain: u64,
        flow: PubkyGrantAuthFlow,
        request: Arc<AuthRequest>,
        loaded: &LoadedFlow,
    ) -> Self {
        Self {
            chain,
            flow,
            request,
            spec: loaded.spec.clone(),
            relay_index: loaded.relay_index,
            relay_base: loaded.relay_base(),
            started_at: loaded.started_at,
            relay_failures: 0,
            unclear_restarts: 0,
            listening_since: Instant::now(),
            probed_at: Mutex::new(None),
            poll_turn: tokio::sync::Mutex::new(()),
        }
    }
}

/// The next thing to do after one poll.
enum Step {
    /// Poll `next` again, after `backoff` if given.
    Waiting {
        next: Arc<Flow>,
        backoff: Option<Duration>,
    },
    /// Signed in: the result is parked.
    Done,
    /// The chain moved to the next relay.
    Switched { next: Arc<Flow> },
}

/// What a wait or poll produced.
pub(crate) enum Outcome {
    Waiting,
    RelayChanged(Arc<AuthRequest>),
    Finished(Box<Finished>),
}

enum Entry {
    Finished(Box<Finished>),
    Flow(Arc<Flow>),
}

/// Holds the poll task after the SDK exchange returned and before the result is concluded,
/// so tests can act while the exchange is "still running".
#[cfg(test)]
#[derive(Default)]
pub(crate) struct ExchangeGate {
    pub reached: tokio::sync::Notify,
    pub release: tokio::sync::Notify,
}

pub(crate) struct SignIns {
    client: PubkyHttpClient,
    /// Plain HTTP client for relay probes.
    probe_http: reqwest::Client,
    relays: Vec<Url>,
    next_chain: AtomicU64,
    pending: Mutex<Option<Arc<Flow>>>,
    finished: Mutex<Option<Finished>>,
    #[cfg(test)]
    gate: Mutex<Option<Arc<ExchangeGate>>>,
    /// Secrets of results the core revoked, after the revoke finished (tests check them).
    #[cfg(test)]
    revoked: Arc<Mutex<Vec<Arc<SecretValue>>>>,
}

/// Where a sign-in poll error came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ErrorSource {
    /// A request to the relay failed: no approval was consumed.
    Relay,
    /// The error names no URL: a relay status/body error or an exchange error. The relay
    /// probe decides; `retryable` says whether an exchange error is worth one more listen.
    NoUrl { retryable: bool },
    /// Another host (the homeserver): the approval was consumed.
    Exchange,
}

/// Transport errors carry their request URL, and relay requests are `{relay base}/{channel}`.
/// Anything that names the relay is the relay's failure, whatever its status.
fn error_source(raw: &str, error: &CoreError, relay_base: Option<&str>) -> ErrorSource {
    if relay_base.is_some_and(|base| raw.contains(&format!("{base}/"))) {
        return ErrorSource::Relay;
    }
    if raw.contains("url (") {
        return ErrorSource::Exchange;
    }
    let retryable = match error {
        CoreError::Network { .. } | CoreError::RateLimited { .. } => true,
        CoreError::Server { status, .. } => *status >= 500,
        _ => false,
    };
    ErrorSource::NoUrl { retryable }
}

/// Whether a probe answer comes from a working http-relay: its `/ack` route answers 200
/// (`true`/`false`) for a known channel and 404 `Not found` for an unknown one. Block pages,
/// proxies' 404s, 5xx and rate limits are not a working relay.
fn relay_answer_ok(status: u16, body: &str) -> bool {
    match status {
        200 => true,
        404 => body.trim() == RELAY_NOT_FOUND_BODY,
        _ => false,
    }
}

/// An error after the relay delivered the approval: same kind, but says that the user has
/// to approve again.
fn after_approval(error: CoreError) -> CoreError {
    let again = |reason: String| format!("sign-in could not be completed, approve again: {reason}");
    match error {
        CoreError::Network { reason } => CoreError::Network {
            reason: again(reason),
        },
        CoreError::Auth { reason } => CoreError::Auth {
            reason: again(reason),
        },
        CoreError::Server { status, reason } => CoreError::Server {
            status,
            reason: again(reason),
        },
        CoreError::RateLimited { reason } => CoreError::RateLimited {
            reason: again(reason),
        },
        other => other,
    }
}

fn backoff(failures: u32) -> Duration {
    let factor = 1_u32 << failures.saturating_sub(1).min(8);
    BACKOFF_BASE.saturating_mul(factor).min(MAX_BACKOFF)
}

impl SignIns {
    pub(crate) fn new(client: PubkyHttpClient, relays: Vec<Url>) -> Result<Self, CoreError> {
        Ok(Self {
            client,
            probe_http: icann_client(PROBE_TIMEOUT)?,
            relays,
            next_chain: AtomicU64::new(1),
            pending: Mutex::new(None),
            finished: Mutex::new(None),
            #[cfg(test)]
            gate: Mutex::new(None),
            #[cfg(test)]
            revoked: Arc::default(),
        })
    }

    /// Secrets of results the core has revoked so far.
    #[cfg(test)]
    pub(crate) fn revoked_secrets(&self) -> Vec<Arc<SecretValue>> {
        lock(&self.revoked).clone()
    }

    /// Revokes a session nobody will use (its grant would stay valid for two years
    /// otherwise). Needs the core's runtime context.
    fn revoke(&self, session: PubkySession, secret: Option<Arc<SecretValue>>) {
        #[cfg(test)]
        let revoked = Arc::clone(&self.revoked);
        tokio::spawn(async move {
            // Best effort: an unreachable homeserver leaves the grant to expire.
            let _ = session.signout().await;
            #[cfg(test)]
            if let Some(secret) = secret {
                lock(&revoked).push(secret);
            }
            #[cfg(not(test))]
            drop(secret);
        });
    }

    /// Asks the flow's relay whether it works, with a short deadline. Never touches the
    /// flow's own channel (a GET there would take the approval away from the listener).
    async fn relay_healthy(&self, flow: &Flow) -> bool {
        let Some(base) = flow.relay_base.as_deref() else {
            return true;
        };
        let url = format!("{base}/{}/ack", new_nonce());
        let Ok(response) = self.probe_http.get(&url).send().await else {
            return false;
        };
        let status = response.status().as_u16();
        let body = response.text().await.unwrap_or_default();
        relay_answer_ok(status, &body)
    }

    /// Probes the relay when a probe is due; `false` when it failed.
    async fn probe_if_due(&self, flow: &Flow) -> bool {
        let due = lock(&flow.probed_at).is_none_or(|at| at.elapsed() >= PROBE_INTERVAL);
        if !due {
            return true;
        }
        let healthy = self.relay_healthy(flow).await;
        if healthy {
            *lock(&flow.probed_at) = Some(Instant::now());
        }
        healthy
    }

    #[cfg(test)]
    pub(crate) fn set_gate(&self, gate: Option<Arc<ExchangeGate>>) {
        *lock(&self.gate) = gate;
    }

    fn new_chain(&self) -> u64 {
        self.next_chain.fetch_add(1, Ordering::Relaxed)
    }

    /// A new flow of `chain` on relay `relay_index`. Needs the core's runtime context (the
    /// relay listener spawns there).
    fn build(
        &self,
        spec: &FlowSpec,
        relay_index: usize,
        chain: u64,
    ) -> Result<Arc<Flow>, CoreError> {
        let relay = self
            .relays
            .get(relay_index)
            .ok_or_else(|| CoreError::internal("no auth relay at that position"))?;
        let nonce = new_nonce();
        let flow = PubkyGrantAuthFlow::builder(&capabilities()?, spec.kind()?, client_id()?)
            .relay(relay.clone())
            .client(self.client.clone())
            .x_callback(x_callback(&nonce))
            .start()?;
        let state = flow
            .save_local()
            .ok_or_else(|| CoreError::internal("sign-in flow has no exportable key"))?;
        let saved = save_flow(state, &nonce, spec, relay_index, now_secs())?;
        let loaded = load_flow(&saved)?;
        let request = AuthRequest::new(
            flow.authorization_url().to_string(),
            nonce,
            spec.flow_type(),
            saved,
        );
        Ok(Arc::new(Flow::new(chain, flow, request, &loaded)))
    }

    /// Drops a parked result nobody collected, revoking its grant.
    fn revoke_parked(&self) {
        let parked = lock(&self.finished).take();
        if let Some(old) = parked {
            self.revoke(old.session, Some(old.result.secret));
        }
    }

    /// Starts a new sign-in on the first relay, replacing a running one. Needs the core's
    /// runtime context.
    pub(crate) fn start(&self, spec: FlowSpec) -> Result<Arc<AuthRequest>, CoreError> {
        let flow = self.build(&spec, 0, self.new_chain())?;
        let request = Arc::clone(&flow.request);
        *lock(&self.pending) = Some(flow);
        self.revoke_parked();
        Ok(request)
    }

    /// Picks a saved flow up again. Resuming the running flow, or while its result waits to
    /// be collected, changes nothing. Needs the core's runtime context.
    pub(crate) fn resume(&self, state: Arc<SecretValue>) -> Result<Arc<AuthRequest>, CoreError> {
        let loaded = load_flow(&state)?;
        if flow_expired(loaded.started_at, now_secs()) {
            return Err(CoreError::auth(
                "the sign-in request expired; start a new one",
            ));
        }
        let mut slot = lock(&self.pending);
        if let Some(current) = slot.as_ref()
            && current.request.pending_state().as_str() == state.as_str()
        {
            return Ok(Arc::clone(&current.request));
        }
        let request = AuthRequest::new(
            loaded.flow.authorization_url.clone(),
            loaded.nonce.clone(),
            loaded.spec.flow_type(),
            Arc::clone(&state),
        );
        if lock(&self.finished).is_some() {
            return Ok(request);
        }
        let flow = PubkyGrantAuthFlow::restore(loaded.flow.clone(), self.client.clone())?;
        *slot = Some(Arc::new(Flow::new(
            self.new_chain(),
            flow,
            Arc::clone(&request),
            &loaded,
        )));
        Ok(request)
    }

    /// The running flow's request, if any.
    pub(crate) fn current(&self) -> Option<Arc<AuthRequest>> {
        lock(&self.pending)
            .as_ref()
            .map(|flow| Arc::clone(&flow.request))
    }

    pub(crate) fn has_pending(&self) -> bool {
        lock(&self.pending).is_some() || lock(&self.finished).is_some()
    }

    /// Stops the running flow. A result already parked stays for the next wait or poll.
    pub(crate) fn cancel(&self) {
        lock(&self.pending).take();
    }

    fn clear_if_current(&self, flow: &Arc<Flow>) {
        let mut slot = lock(&self.pending);
        if slot
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, flow))
        {
            *slot = None;
        }
    }

    /// The same flow on a fresh relay listener, unless it stopped being current.
    fn restart(
        &self,
        flow: &Arc<Flow>,
        relay_failures: u32,
        unclear: bool,
    ) -> Result<Arc<Flow>, CoreError> {
        let mut slot = lock(&self.pending);
        if !slot
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, flow))
        {
            return Err(CoreError::Cancelled);
        }
        let loaded = load_flow(&flow.request.pending_state())?;
        let listener = PubkyGrantAuthFlow::restore(loaded.flow.clone(), self.client.clone())?;
        let mut fresh = Flow::new(flow.chain, listener, Arc::clone(&flow.request), &loaded);
        fresh.relay_failures = relay_failures;
        fresh.unclear_restarts = flow.unclear_restarts + u32::from(unclear);
        let fresh = Arc::new(fresh);
        *slot = Some(Arc::clone(&fresh));
        Ok(fresh)
    }

    /// After a relay failure: restart the listener, or move the chain to the next relay, or
    /// give up when every relay failed.
    fn relay_failed(&self, flow: &Arc<Flow>) -> Result<Step, CoreError> {
        let failures = if flow.listening_since.elapsed() >= RELAY_HEALTHY_AFTER {
            1
        } else {
            flow.relay_failures + 1
        };
        if failures < RELAY_FAILURES_BEFORE_SWITCH {
            let next = self.restart(flow, failures, false)?;
            return Ok(Step::Waiting {
                next,
                backoff: Some(backoff(failures)),
            });
        }
        let next_index = flow.relay_index + 1;
        if next_index >= self.relays.len() {
            self.clear_if_current(flow);
            let tried: Vec<&str> = self.relays.iter().map(Url::as_str).collect();
            return Err(CoreError::Network {
                reason: format!(
                    "the sign-in relay is unreachable (tried {})",
                    tried.join(", ")
                ),
            });
        }
        let mut slot = lock(&self.pending);
        if !slot
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, flow))
        {
            return Err(CoreError::Cancelled);
        }
        let next = self.build(&flow.spec, next_index, flow.chain)?;
        *slot = Some(Arc::clone(&next));
        Ok(Step::Switched { next })
    }

    /// One look at `flow`. Runs in a task that is never aborted (see `poll_detached`): once
    /// the relay delivered the approval (the SDK acknowledges it at once), the exchange must
    /// finish even if the caller goes away, or the approval is lost.
    async fn poll(&self, flow: &Arc<Flow>) -> Result<Step, CoreError> {
        let _turn = flow.poll_turn.lock().await;
        let current = lock(&self.pending).clone();
        match current {
            Some(current) if Arc::ptr_eq(&current, flow) => {}
            // A restart (same link) or a relay switch (new link) of this sign-in replaced it.
            Some(current) if current.chain == flow.chain => {
                return Ok(if Arc::ptr_eq(&current.request, &flow.request) {
                    Step::Waiting {
                        next: current,
                        backoff: None,
                    }
                } else {
                    Step::Switched { next: current }
                });
            }
            _ => {
                let parked = lock(&self.finished)
                    .as_ref()
                    .is_some_and(|done| done.chain == flow.chain);
                return if parked {
                    Ok(Step::Done)
                } else {
                    Err(CoreError::Cancelled)
                };
            }
        }
        if flow_expired(flow.started_at, now_secs()) {
            self.clear_if_current(flow);
            return Err(CoreError::auth(
                "the sign-in request expired; start a new one",
            ));
        }
        match flow.flow.try_poll_once().await {
            Ok(None) => {
                if self.probe_if_due(flow).await {
                    Ok(Step::Waiting {
                        next: Arc::clone(flow),
                        backoff: None,
                    })
                } else {
                    self.relay_failed(flow)
                }
            }
            Ok(Some(session)) => {
                #[cfg(test)]
                {
                    let gate = lock(&self.gate).clone();
                    if let Some(gate) = gate {
                        gate.reached.notify_one();
                        gate.release.notified().await;
                    }
                }
                let result = match signed_in(&session).await {
                    Ok(result) => result,
                    Err(error) => {
                        self.clear_if_current(flow);
                        self.revoke(session, None);
                        return Err(error);
                    }
                };
                self.park(flow, session, result)
            }
            Err(error) => {
                let raw = error.to_string();
                let error = CoreError::from(error);
                match error_source(&raw, &error, flow.relay_base.as_deref()) {
                    ErrorSource::Relay => self.relay_failed(flow),
                    ErrorSource::NoUrl { retryable } => {
                        if !self.relay_healthy(flow).await {
                            self.relay_failed(flow)
                        } else if retryable && flow.unclear_restarts < MAX_UNCLEAR_RESTARTS {
                            let next = self.restart(flow, flow.relay_failures, true)?;
                            Ok(Step::Waiting {
                                next,
                                backoff: Some(BACKOFF_BASE),
                            })
                        } else {
                            self.clear_if_current(flow);
                            Err(after_approval(error))
                        }
                    }
                    ErrorSource::Exchange => {
                        self.clear_if_current(flow);
                        Err(after_approval(error))
                    }
                }
            }
        }
    }

    /// Parks a result for the next wait or poll. A result of a sign-in the user replaced with
    /// a new one is revoked instead: the app is not waiting for it any more.
    fn park(
        &self,
        flow: &Arc<Flow>,
        session: PubkySession,
        result: SignedIn,
    ) -> Result<Step, CoreError> {
        let mut slot = lock(&self.pending);
        let current_chain = slot.as_ref().map(|current| current.chain);
        match current_chain {
            Some(chain) if chain != flow.chain => {
                drop(slot);
                self.revoke(session, Some(result.secret));
                return Err(CoreError::Cancelled);
            }
            Some(_) => *slot = None,
            None => {}
        }
        let done = Finished {
            chain: flow.chain,
            session,
            result,
        };
        let older = lock(&self.finished).replace(done);
        if let Some(older) = older {
            self.revoke(older.session, Some(older.result.secret));
        }
        Ok(Step::Done)
    }

    /// `poll` in a task that is never aborted.
    async fn poll_detached(self: Arc<Self>, flow: Arc<Flow>) -> Result<Step, CoreError> {
        tokio::spawn(async move { self.poll(&flow).await })
            .await
            .unwrap_or_else(|_| Err(CoreError::internal("the sign-in task panicked")))
    }

    fn take_finished(&self) -> Option<Box<Finished>> {
        lock(&self.finished).take().map(Box::new)
    }

    /// A parked result, else the running flow.
    fn entry(&self) -> Result<Entry, CoreError> {
        if let Some(done) = self.take_finished() {
            return Ok(Entry::Finished(done));
        }
        if let Some(flow) = lock(&self.pending).clone() {
            return Ok(Entry::Flow(flow));
        }
        // The flow may have finished between the two looks.
        self.take_finished()
            .map(Entry::Finished)
            .ok_or_else(|| CoreError::auth("no sign-in is running"))
    }

    fn collect(&self) -> Result<Outcome, CoreError> {
        self.take_finished()
            .map(Outcome::Finished)
            .ok_or_else(|| CoreError::internal("sign-in result went missing"))
    }

    /// Waits for the approval, a relay switch or the end of the flow.
    pub(crate) async fn wait(self: Arc<Self>) -> Result<Outcome, CoreError> {
        let mut flow = match self.entry()? {
            Entry::Finished(done) => return Ok(Outcome::Finished(done)),
            Entry::Flow(flow) => flow,
        };
        loop {
            match Arc::clone(&self).poll_detached(Arc::clone(&flow)).await? {
                Step::Done => return self.collect(),
                Step::Switched { next } => {
                    return Ok(Outcome::RelayChanged(Arc::clone(&next.request)));
                }
                Step::Waiting { next, backoff } => {
                    tokio::time::sleep(backoff.unwrap_or(POLL_INTERVAL)).await;
                    flow = next;
                }
            }
        }
    }

    /// One look without waiting.
    pub(crate) async fn poll_once(self: Arc<Self>) -> Result<Outcome, CoreError> {
        let flow = match self.entry()? {
            Entry::Finished(done) => return Ok(Outcome::Finished(done)),
            Entry::Flow(flow) => flow,
        };
        match Arc::clone(&self).poll_detached(flow).await? {
            Step::Done => self.collect(),
            Step::Switched { next } => Ok(Outcome::RelayChanged(Arc::clone(&next.request))),
            Step::Waiting { .. } => Ok(Outcome::Waiting),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn poll_errors_are_attributed_by_url() {
        let relay = Some("http://localhost:4000/inbox");
        let net = || CoreError::Network {
            reason: String::new(),
        };
        let relay_err =
            "HTTP transport error: error sending request for url (http://localhost:4000/inbox/abc)";
        assert_eq!(error_source(relay_err, &net(), relay), ErrorSource::Relay);
        // Whatever the status: an error that names the relay is the relay's.
        let denied = CoreError::Auth {
            reason: String::new(),
        };
        assert_eq!(error_source(relay_err, &denied, relay), ErrorSource::Relay);
        let hs_err = "HTTP transport error: error sending request for url (http://localhost:6286/auth/grant/session)";
        assert_eq!(error_source(hs_err, &net(), relay), ErrorSource::Exchange);
        // A prefix of another host does not count as the relay.
        let near = "error sending request for url (http://localhost:40001/x)";
        assert_eq!(
            error_source(near, &net(), Some("http://localhost:4000")),
            ErrorSource::Exchange
        );
        let server = |status| CoreError::Server {
            status,
            reason: String::new(),
        };
        assert_eq!(
            error_source("Server responded with an error: 503", &server(503), relay),
            ErrorSource::NoUrl { retryable: true }
        );
        assert_eq!(
            error_source("Server responded with an error: 403", &server(403), relay),
            ErrorSource::NoUrl { retryable: false }
        );
    }

    #[test]
    fn only_a_working_relay_answers_the_probe() {
        assert!(relay_answer_ok(404, "Not found"));
        assert!(relay_answer_ok(200, "false"));
        assert!(!relay_answer_ok(404, "<html>404 Not Found</html>"));
        assert!(!relay_answer_ok(403, "Forbidden"));
        assert!(!relay_answer_ok(451, ""));
        assert!(!relay_answer_ok(502, "Bad Gateway"));
        assert!(!relay_answer_ok(429, ""));
    }

    #[test]
    fn errors_after_approval_say_approve_again() {
        let err = after_approval(CoreError::Network {
            reason: "timeout".into(),
        });
        assert!(
            matches!(&err, CoreError::Network { reason } if reason.contains("approve again")),
            "{err:?}"
        );
        assert!(matches!(
            after_approval(CoreError::Cancelled),
            CoreError::Cancelled
        ));
    }

    fn offline_sign_ins(runtime: &tokio::runtime::Runtime, relays: &[&str]) -> Arc<SignIns> {
        let _context = runtime.enter();
        let mut config = crate::config::default_core_config();
        config.dht_enabled = false;
        config.pkarr_relays = vec!["http://127.0.0.1:9".into()];
        config.auth_relay_urls = relays.iter().map(|r| (*r).to_string()).collect();
        let valid = config.validate().unwrap();
        let client = crate::config::build_client(&valid).unwrap();
        Arc::new(SignIns::new(client, valid.auth_relay_urls).unwrap())
    }

    #[test]
    fn a_poll_racing_a_listener_restart_keeps_waiting() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let sign_ins = offline_sign_ins(&runtime, &["http://127.0.0.1:9/inbox"]);
        let _context = runtime.enter();
        sign_ins.start(FlowSpec::SignIn).unwrap();
        let old = lock(&sign_ins.pending).clone().unwrap();
        // What a waiting `await_sign_in` does after a relay failure.
        let fresh = sign_ins.restart(&old, 1, false).unwrap();
        // A poll that still holds the old listener is told to keep waiting on the new one.
        match runtime.block_on(sign_ins.poll(&old)) {
            Ok(Step::Waiting {
                next,
                backoff: None,
            }) => assert!(Arc::ptr_eq(&next, &fresh)),
            _ => panic!("a stale poll of the same sign-in must keep waiting"),
        }
        assert!(matches!(
            runtime.block_on(Arc::clone(&sign_ins).poll_once()),
            Ok(Outcome::Waiting)
        ));
        // A cancelled sign-in is reported as cancelled.
        sign_ins.cancel();
        assert!(matches!(
            runtime.block_on(sign_ins.poll(&fresh)),
            Err(CoreError::Cancelled)
        ));
    }

    #[test]
    fn a_poll_of_a_switched_sign_in_reports_the_switch() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let sign_ins = offline_sign_ins(
            &runtime,
            &["http://127.0.0.1:9/inbox", "http://127.0.0.1:7/inbox"],
        );
        let _context = runtime.enter();
        sign_ins.start(FlowSpec::SignIn).unwrap();
        let old = lock(&sign_ins.pending).clone().unwrap();
        let worn = sign_ins
            .restart(&old, RELAY_FAILURES_BEFORE_SWITCH - 1, false)
            .unwrap();
        let switched = match sign_ins.relay_failed(&worn) {
            Ok(Step::Switched { next }) => next,
            _ => panic!("the fourth failure moves to the next relay"),
        };
        assert_eq!(switched.relay_index, 1);
        assert_eq!(switched.chain, old.chain);
        match runtime.block_on(sign_ins.poll(&old)) {
            Ok(Step::Switched { next }) => assert!(Arc::ptr_eq(&next, &switched)),
            _ => panic!("a stale poll learns about the switch"),
        }
    }

    #[test]
    fn backoff_doubles_up_to_the_cap() {
        assert_eq!(backoff(1), BACKOFF_BASE);
        assert_eq!(backoff(2), BACKOFF_BASE * 2);
        assert_eq!(backoff(3), BACKOFF_BASE * 4);
        assert!(backoff(30) <= MAX_BACKOFF);
    }
}
