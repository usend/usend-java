package et.usend;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client for the usend API. No dependencies beyond the JDK (Java 11+).
 *
 * <pre>{@code
 * Usend usend = new Usend(System.getenv("USEND_API_KEY"));
 * Map<String, Object> sent = usend.emails.send(Map.of(
 *     "from", "Acme <hello@acme.com>",
 *     "to", List.of("ann@example.com"),
 *     "subject", "Hello",
 *     "html", "<p>It works!</p>"));
 * }</pre>
 *
 * Methods return the API's JSON as {@code Map<String,Object>} / {@code List<Object>} (see {@link Json}) and
 * throw {@link UsendException} on API or network errors. Rate-limited (429 {@code rate_limit_exceeded}) requests,
 * and idempotent requests that hit a 5xx or a network error, are retried with backoff.
 */
public final class Usend {
    public static final String VERSION = "1.3.0";
    public static final String DEFAULT_BASE_URL = "https://api.usend.et";
    static final String DEFAULT_SMTP_HOST = "smtp.usend.et";

    /** Client options. All fields are optional. */
    public static final class Options {
        String baseUrl;
        Duration timeout = Duration.ofSeconds(30);
        int maxRetries = 2;
        HttpClient httpClient;

        public Options baseUrl(String v) { this.baseUrl = v; return this; }
        public Options timeout(Duration v) { this.timeout = v; return this; }
        /** Retries after the first attempt (default 2). */
        public Options maxRetries(int v) { this.maxRetries = Math.max(0, v); return this; }
        public Options httpClient(HttpClient v) { this.httpClient = v; return this; }
    }

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final HttpClient http;

    public final Emails emails = new Emails(this);
    public final Batch batch = new Batch(this);
    public final Received received = new Received(this);
    public final Templates templates = new Templates(this);
    public final Domains domains = new Domains(this);
    public final ApiKeys apiKeys = new ApiKeys(this);
    public final Segments segments = new Segments(this);
    public final Contacts contacts = new Contacts(this);
    public final Topics topics = new Topics(this);
    public final ShortLinks shortLinks = new ShortLinks(this);
    public final Broadcasts broadcasts = new Broadcasts(this);
    public final Webhooks webhooks = new Webhooks(this);
    public final Suppressions suppressions = new Suppressions(this);
    public final ContactProperties contactProperties = new ContactProperties(this);
    public final Events events = new Events(this);
    public final Automations automations = new Automations(this);
    public final Inboxes inboxes = new Inboxes(this);
    public final Logs logs = new Logs(this);
    public final Tools tools = new Tools(this);
    public final OAuth oauth = new OAuth(this);

    /** Uses {@code USEND_API_KEY} from the environment. */
    public Usend() { this(null, new Options()); }

    public Usend(String apiKey) { this(apiKey, new Options()); }

    public Usend(String apiKey, Options options) {
        String key = apiKey != null && !apiKey.isEmpty() ? apiKey : System.getenv("USEND_API_KEY");
        if (key == null || key.isEmpty()) {
            throw new UsendException("Missing API key. Pass it to new Usend(\"us_...\") or set USEND_API_KEY.", "missing_api_key", 0);
        }
        Options o = options == null ? new Options() : options;
        String base = o.baseUrl;
        if (base == null || base.isEmpty()) base = System.getenv("USEND_BASE_URL");
        if (base == null || base.isEmpty()) base = DEFAULT_BASE_URL;
        this.apiKey = key;
        this.baseUrl = base.replaceAll("/+$", "");
        this.timeout = o.timeout;
        this.maxRetries = o.maxRetries;
        this.http = o.httpClient != null ? o.httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** A fresh random idempotency key. */
    public static String newIdempotencyKey() { return UUID.randomUUID().toString(); }

    /** SMTP settings for this key on port 465. */
    public SmtpConfig smtpConfig() { return smtpConfig(465, null); }

    /** SMTP settings for this key. {@code port} is 465 or 587; {@code host} overrides the derived host (may be null). */
    public SmtpConfig smtpConfig(int port, String host) { return smtpConfig(apiKey, port, host, baseUrl); }

    /**
     * SMTP settings for sending through usend; the API key is the password. The host is {@code smtp.usend.et};
     * for a self-hosted platform pass {@code host}, set {@code USEND_SMTP_HOST}, or pass {@code baseUrl}
     * ({@code https://api.x.com} gives {@code smtp.x.com}).
     */
    public static SmtpConfig smtpConfig(String apiKey, int port, String host, String baseUrl) {
        String key = apiKey != null && !apiKey.isEmpty() ? apiKey : System.getenv("USEND_API_KEY");
        if (key == null || key.isEmpty()) {
            throw new UsendException("Missing API key. Pass it to smtpConfig(\"us_...\") or set USEND_API_KEY.", "missing_api_key", 0);
        }
        if (port != 465 && port != 587) {
            throw new UsendException("usend accepts SMTP on port 465 (TLS) or 587 (STARTTLS).", "invalid_port", 0);
        }
        String h = host;
        if (h == null || h.isEmpty()) h = System.getenv("USEND_SMTP_HOST");
        if (h == null || h.isEmpty()) h = smtpHostFor(baseUrl);
        return new SmtpConfig(h, port, key);
    }

    static String smtpHostFor(String baseUrl) {
        if (baseUrl != null && !baseUrl.isEmpty()) {
            try {
                String hostname = URI.create(baseUrl).getHost();
                if (hostname != null && hostname.startsWith("api.")) return "smtp." + hostname.substring(4);
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_SMTP_HOST;
    }

    // ---- request core ----

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String qs(Map<String, ?> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            sb.append(sb.length() == 0 ? '?' : '&').append(enc(e.getKey())).append('=').append(enc(String.valueOf(e.getValue())));
        }
        return sb.toString();
    }

    private static Map<String, String> idem(String key) {
        Map<String, String> h = new LinkedHashMap<>();
        if (key != null && !key.isEmpty()) h.put("Idempotency-Key", key);
        return h;
    }

    @SuppressWarnings("unchecked")
    <T> T request(String method, String path, Object body, Map<String, String> headers) {
        String json = body == null ? null : Json.stringify(body);
        boolean safe = method.equals("GET") || method.equals("DELETE") || method.equals("PUT")
                || (headers != null && headers.containsKey("Idempotency-Key"));
        UsendException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "usend-java/" + VERSION)
                    .header("Accept", "application/json");
            if (headers != null) headers.forEach(rb::header);
            if (json != null) {
                rb.header("Content-Type", "application/json");
                rb.method(method, HttpRequest.BodyPublishers.ofString(json));
            } else if (method.equals("GET") || method.equals("DELETE")) {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                rb.method(method, HttpRequest.BodyPublishers.ofString("{}"));
                rb.header("Content-Type", "application/json");
            }
            long waitMs = backoff(attempt);
            try {
                HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                String text = res.body() == null ? "" : res.body();
                int status = res.statusCode();
                if (status >= 200 && status < 300) {
                    if (text.isBlank()) return (T) new LinkedHashMap<String, Object>();
                    try {
                        return (T) Json.parse(text);
                    } catch (IllegalArgumentException e) {
                        throw new UsendException("Invalid JSON in response", "application_error", status, e);
                    }
                }
                String name = "application_error";
                String message = "HTTP " + status;
                try {
                    Object parsed = Json.parse(text);
                    if (parsed instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) parsed;
                        if (m.get("name") instanceof String) name = (String) m.get("name");
                        if (m.get("message") instanceof String) message = (String) m.get("message");
                        // OAuth endpoints answer { error, error_description }
                        if (m.get("name") == null && m.get("error") instanceof String) {
                            name = (String) m.get("error");
                            if (m.get("error_description") instanceof String) message = (String) m.get("error_description");
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // keep the generic error
                }
                last = new UsendException(message, name, status);
                boolean retry = (status == 429 && name.equals("rate_limit_exceeded")) || (safe && status >= 500 && status != 501);
                if (!retry || attempt == maxRetries) throw last;
                String ra = res.headers().firstValue("retry-after").orElse(null);
                if (ra != null) {
                    try {
                        waitMs = Math.min(30_000L, Math.max(0L, (long) (Double.parseDouble(ra.trim()) * 1000)));
                    } catch (NumberFormatException ignored) {
                        // keep the computed backoff
                    }
                }
            } catch (IOException e) {
                last = new UsendException(String.valueOf(e.getMessage()), "application_error", 0, e);
                if (!safe || attempt == maxRetries) throw last;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UsendException("Interrupted", "application_error", 0, e);
            }
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UsendException("Interrupted", "application_error", 0, e);
            }
        }
        throw last != null ? last : new UsendException("Request failed", "application_error", 0);
    }

    /** Downloads a file (attachment or raw message) as bytes. Not retried. */
    byte[] download(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "usend-java/" + VERSION)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() >= 200 && res.statusCode() < 300) return res.body();
            String name = "application_error";
            String message = "HTTP " + res.statusCode();
            try {
                Object parsed = Json.parse(new String(res.body(), StandardCharsets.UTF_8));
                if (parsed instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) parsed;
                    if (m.get("name") instanceof String) name = (String) m.get("name");
                    if (m.get("message") instanceof String) message = (String) m.get("message");
                }
            } catch (IllegalArgumentException ignored) {
                // keep the generic error
            }
            throw new UsendException(message, name, res.statusCode());
        } catch (IOException e) {
            throw new UsendException(String.valueOf(e.getMessage()), "application_error", 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UsendException("Interrupted", "application_error", 0, e);
        }
    }

    /** Sending statistics: {@code totals} and a {@code daily} series. */
    public Map<String, Object> stats() { return stats(null); }

    /** @param params for example {@code Map.of("days", 30)} */
    public Map<String, Object> stats(Map<String, ?> params) { return request("GET", "/stats" + qs(params), null, null); }

    private static long backoff(int attempt) {
        return Math.min(8_000L, 500L << attempt);
    }

    // ---- resources ----

    /** Shared base for resources with a path. */
    public abstract static class Resource {
        final Usend c;
        Resource(Usend c) { this.c = c; }
    }

    /** create / list / get / update / remove over one collection. */
    public abstract static class Crud extends Resource {
        private final String path;

        Crud(Usend c, String path) {
            super(c);
            this.path = path;
        }

        public Map<String, Object> create(Map<String, Object> body) { return c.request("POST", path, body, null); }
        public Map<String, Object> list() { return list(null); }
        public Map<String, Object> list(Map<String, ?> params) { return c.request("GET", path + qs(params), null, null); }
        public Map<String, Object> get(String id) { return c.request("GET", path + "/" + enc(id), null, null); }
        public Map<String, Object> update(String id, Map<String, Object> body) { return c.request("PATCH", path + "/" + enc(id), body, null); }
        public Map<String, Object> remove(String id) { return c.request("DELETE", path + "/" + enc(id), null, null); }
    }

    public static final class Emails extends Resource {
        public Map<String, Object> metrics() { return metrics(null); }
        public Map<String, Object> metrics(Map<String, ?> params) { return c.request("GET", "/emails/metrics" + qs(params), null, null); }
        public Map<String, Object> share(String id, Map<String, Object> body) { return c.request("POST", "/emails/" + enc(id) + "/share", body, null); }
        public Map<String, Object> test(Map<String, Object> body) { return c.request("POST", "/emails/test", body, null); }
        public Map<String, Object> insights(String id) { return c.request("GET", "/emails/" + enc(id) + "/insights", null, null); }
        Emails(Usend c) { super(c); }

        public Map<String, Object> send(Map<String, Object> email) { return send(email, null); }

        /** Sends an email. A repeated {@code idempotencyKey} returns the first result instead of sending twice. */
        public Map<String, Object> send(Map<String, Object> email, String idempotencyKey) {
            return c.request("POST", "/emails", email, idem(idempotencyKey));
        }

        public Map<String, Object> get(String id) { return c.request("GET", "/emails/" + enc(id), null, null); }
        public Map<String, Object> list() { return list(null); }
        public Map<String, Object> list(Map<String, ?> params) { return c.request("GET", "/emails" + qs(params), null, null); }

        /** Reschedules a scheduled email; {@code scheduledAt} is ISO 8601 or natural language. */
        public Map<String, Object> update(String id, String scheduledAt) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("scheduled_at", scheduledAt);
            return c.request("PATCH", "/emails/" + enc(id), b, null);
        }

        public Map<String, Object> cancel(String id) { return c.request("POST", "/emails/" + enc(id) + "/cancel", null, null); }

        /** The email's events, oldest first. */
        public List<Object> events(String id) { return c.request("GET", "/emails/" + enc(id) + "/events", null, null); }

        public Map<String, Object> attachments(String emailId) { return c.request("GET", "/emails/" + enc(emailId) + "/attachments", null, null); }

        public Map<String, Object> attachment(String emailId, String attachmentId) {
            return c.request("GET", "/emails/" + enc(emailId) + "/attachments/" + enc(attachmentId), null, null);
        }
    }

    public static final class Batch extends Resource {
        Batch(Usend c) { super(c); }

        public Map<String, Object> send(List<Map<String, Object>> emails) { return send(emails, null, null); }

        /** @param validation {@code "strict"} or {@code "permissive"} (may be null) */
        public Map<String, Object> send(List<Map<String, Object>> emails, String idempotencyKey, String validation) {
            Map<String, String> h = idem(idempotencyKey);
            if (validation != null && !validation.isEmpty()) h.put("x-batch-validation", validation);
            return c.request("POST", "/emails/batch", emails, h);
        }
    }

    /** Received (inbound) emails. */
    public static final class Received extends Resource {

        /** The raw RFC 822 message of a received email. */
        public byte[] raw(String id) { return c.download("/inbound/emails/" + enc(id) + "/raw"); }

        /** The bytes of a received email's attachment. */
        public byte[] attachment(String id, String attachmentId) {
            return c.download("/inbound/emails/" + enc(id) + "/attachments/" + enc(attachmentId));
        }

        Received(Usend c) { super(c); }
        public Map<String, Object> list() { return list(null); }
        public Map<String, Object> list(Map<String, ?> params) { return c.request("GET", "/inbound/emails" + qs(params), null, null); }
        public Map<String, Object> get(String id) { return c.request("GET", "/inbound/emails/" + enc(id), null, null); }
    }

    public static final class Templates extends Crud {
        public Map<String, Object> versions(String id) { return versions(id, null); }
        public Map<String, Object> versions(String id, Map<String, ?> params) { return c.request("GET", "/templates/" + enc(id) + "/versions" + qs(params), null, null); }
        public Map<String, Object> version(String id, String versionId) { return c.request("GET", "/templates/" + enc(id) + "/versions/" + enc(versionId), null, null); }
        public Map<String, Object> restoreVersion(String id, String versionId) { return c.request("POST", "/templates/" + enc(id) + "/versions/" + enc(versionId) + "/restore", null, null); }
        Templates(Usend c) { super(c, "/templates"); }
        public Map<String, Object> publish(String idOrAlias) { return c.request("POST", "/templates/" + enc(idOrAlias) + "/publish", null, null); }
        public Map<String, Object> duplicate(String idOrAlias) { return c.request("POST", "/templates/" + enc(idOrAlias) + "/duplicate", null, null); }
    }

    public static final class Domains extends Crud {
        public Map<String, Object> createClaim(Map<String, Object> body) { return c.request("POST", "/domains/claim", body, null); }
        public Map<String, Object> getClaim(String id) { return c.request("GET", "/domains/claim/" + enc(id), null, null); }
        public Map<String, Object> verifyClaim(String id) { return c.request("POST", "/domains/claim/" + enc(id) + "/verify", null, null); }
        public Map<String, Object> insights(String id) { return c.request("GET", "/domains/" + enc(id) + "/insights", null, null); }
        Domains(Usend c) { super(c, "/domains"); }
        public Map<String, Object> verify(String id) { return c.request("POST", "/domains/" + enc(id) + "/verify", null, null); }

        /** Publishes the domain's DNS records at Cloudflare with a one-time API token (never stored). */
        public Map<String, Object> autoConfigure(String id, String apiToken) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("provider", "cloudflare");
            b.put("api_token", apiToken);
            return c.request("POST", "/domains/" + enc(id) + "/auto-configure", b, null);
        }
    }

    public static final class ApiKeys extends Resource {
        public Map<String, Object> update(String id, Map<String, Object> body) { return c.request("PATCH", "/api-keys/" + enc(id), body, null); }
        ApiKeys(Usend c) { super(c); }
        public Map<String, Object> create(Map<String, Object> body) { return c.request("POST", "/api-keys", body, null); }
        public Map<String, Object> list() { return c.request("GET", "/api-keys", null, null); }
        public Map<String, Object> remove(String id) { return c.request("DELETE", "/api-keys/" + enc(id), null, null); }
    }

    public static final class Segments extends Resource {
        public Map<String, Object> update(String id, Map<String, Object> body) { return c.request("PATCH", "/segments/" + enc(id), body, null); }
        public Map<String, Object> contacts(String id) { return contacts(id, null); }
        public Map<String, Object> contacts(String id, Map<String, ?> params) { return c.request("GET", "/segments/" + enc(id) + "/contacts" + qs(params), null, null); }
        public Map<String, Object> metrics(String id) { return c.request("GET", "/segments/" + enc(id) + "/metrics", null, null); }
        Segments(Usend c) { super(c); }
        public Map<String, Object> create(Map<String, Object> body) { return c.request("POST", "/segments", body, null); }
        public Map<String, Object> list() { return c.request("GET", "/segments", null, null); }
        public Map<String, Object> get(String id) { return c.request("GET", "/segments/" + enc(id), null, null); }
        public Map<String, Object> remove(String id) { return c.request("DELETE", "/segments/" + enc(id), null, null); }
    }

    public static final class Contacts extends Crud {
        public Map<String, Object> stats() { return c.request("GET", "/contacts/stats", null, null); }
        public Map<String, Object> importContacts(Map<String, Object> body) { return c.request("POST", "/contacts/import", body, null); }
        public Map<String, Object> createImport(Map<String, Object> body) { return c.request("POST", "/contacts/imports", body, null); }
        public Map<String, Object> listImports() { return listImports(null); }
        public Map<String, Object> listImports(Map<String, ?> params) { return c.request("GET", "/contacts/imports" + qs(params), null, null); }
        public Map<String, Object> getImport(String id) { return c.request("GET", "/contacts/imports/" + enc(id), null, null); }
        Contacts(Usend c) { super(c, "/contacts"); }

        public Map<String, Object> segments(String idOrEmail) { return c.request("GET", "/contacts/" + enc(idOrEmail) + "/segments", null, null); }

        public Map<String, Object> addToSegment(String idOrEmail, String segmentId) {
            return c.request("POST", "/contacts/" + enc(idOrEmail) + "/segments/" + enc(segmentId), null, null);
        }

        public Map<String, Object> removeFromSegment(String idOrEmail, String segmentId) {
            return c.request("DELETE", "/contacts/" + enc(idOrEmail) + "/segments/" + enc(segmentId), null, null);
        }

        /** The contact's topic subscriptions. */
        public List<Object> topics(String idOrEmail) { return c.request("GET", "/contacts/" + enc(idOrEmail) + "/topics", null, null); }

        /** Body: {@code {"topics": [{"id": "...", "subscription": "opt_in"|"opt_out"}]}}. */
        public Map<String, Object> updateTopics(String idOrEmail, Map<String, Object> body) {
            return c.request("PATCH", "/contacts/" + enc(idOrEmail) + "/topics", body, null);
        }
    }

    public static final class Topics extends Crud {
        Topics(Usend c) { super(c, "/topics"); }
    }

    /**
     * Short links on the shared tracking host that redirect to a target URL and count clicks. Create with
     * {@code {"target_url": "...", "code": "sale"}}; update takes {@code target_url}, {@code title}, {@code disabled} and
     * {@code expires_at} (null clears the expiry).
     */
    public static final class ShortLinks extends Crud {
        ShortLinks(Usend c) { super(c, "/short-links"); }

        /** Click statistics for the last {@code days} days (1 to 365; null = 30). */
        public Map<String, Object> stats(String id, Integer days) {
            return c.request("GET", "/short-links/" + enc(id) + "/stats" + qs(days == null ? null : Map.of("days", days)), null, null);
        }
    }

    public static final class Broadcasts extends Crud {

        public static final List<String> RECIPIENT_TYPES = List.of(
                "sent", "delivered", "opened", "clicked", "bounced", "complained", "unsubscribed", "suppressed");

        /**
         * Recipients who had an event. {@code type} is required: sent, delivered, opened, clicked, bounced,
         * complained, unsubscribed or suppressed. Rows are {@code {id, contact_id, email, count?, bounce_type?, clicked_links?}}.
         */
        public Map<String, Object> recipients(String id, String type) { return recipients(id, type, null); }

        /** Extra query: {@code limit}, {@code after}, {@code before}, {@code email}, {@code bounce_type}. */
        public Map<String, Object> recipients(String id, String type, Map<String, ?> params) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("type", type);
            if (params != null) q.putAll(params);
            q.put("type", type);
            return c.request("GET", "/broadcasts/" + enc(id) + "/recipients" + qs(q), null, null);
        }
        public Map<String, Object> cancel(String id) { return c.request("POST", "/broadcasts/" + enc(id) + "/cancel", null, null); }
        public Map<String, Object> duplicate(String id) { return c.request("POST", "/broadcasts/" + enc(id) + "/duplicate", null, null); }
        public Map<String, Object> clickedLinks(String id) { return c.request("GET", "/broadcasts/" + enc(id) + "/clicked-links", null, null); }
        public Map<String, Object> performance(String id) { return c.request("GET", "/broadcasts/" + enc(id) + "/performance", null, null); }
        Broadcasts(Usend c) { super(c, "/broadcasts"); }

        public Map<String, Object> send(String id) { return send(id, null); }

        /** Sends now, or at {@code scheduledAt} when given. */
        public Map<String, Object> send(String id, String scheduledAt) {
            Map<String, Object> b = new LinkedHashMap<>();
            if (scheduledAt != null && !scheduledAt.isEmpty()) b.put("scheduled_at", scheduledAt);
            return c.request("POST", "/broadcasts/" + enc(id) + "/send", b, null);
        }

        public Map<String, Object> test(String id, String to) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("to", to);
            return c.request("POST", "/broadcasts/" + enc(id) + "/test", b, null);
        }
    }

    public static final class Webhooks extends Crud {
        public Map<String, Object> listEvents() { return listEvents(null); }
        public Map<String, Object> listEvents(Map<String, ?> params) { return c.request("GET", "/webhooks/events" + qs(params), null, null); }
        public Map<String, Object> getEvent(String id) { return c.request("GET", "/webhooks/events/" + enc(id), null, null); }
        public Map<String, Object> replayEvent(String id) { return c.request("POST", "/webhooks/events/" + enc(id) + "/replay", null, null); }
        public Map<String, Object> eventAttempts(String id) { return eventAttempts(id, null); }
        public Map<String, Object> eventAttempts(String id, Map<String, ?> params) { return c.request("GET", "/webhooks/events/" + enc(id) + "/attempts" + qs(params), null, null); }
        public Map<String, Object> rotateSecret(String id) { return c.request("POST", "/webhooks/" + enc(id) + "/rotate-secret", null, null); }
        Webhooks(Usend c) { super(c, "/webhooks"); }

        public Map<String, Object> deliveries(String id) { return deliveries(id, null); }

        public Map<String, Object> deliveries(String id, Map<String, ?> params) {
            return c.request("GET", "/webhooks/" + enc(id) + "/deliveries" + qs(params), null, null);
        }

        public Map<String, Object> replay(String id, String deliveryId) {
            return c.request("POST", "/webhooks/" + enc(id) + "/deliveries/" + enc(deliveryId) + "/replay", null, null);
        }

        /** Same as {@link Webhook#verify(String, Map, String)}. */
        public Map<String, Object> verify(String payload, Map<String, String> headers, String secret) {
            return Webhook.verify(payload, headers, secret);
        }
    }

    public static final class Suppressions extends Resource {
        public Map<String, Object> get(String id) { return c.request("GET", "/suppressions/" + enc(id), null, null); }
        public Map<String, Object> batchCreate(Map<String, Object> body) { return c.request("POST", "/suppressions/batch", body, null); }
        public Map<String, Object> batchRemove(Map<String, Object> body) { return c.request("DELETE", "/suppressions/batch", body, null); }
        Suppressions(Usend c) { super(c); }
        public Map<String, Object> list() { return list(null); }
        public Map<String, Object> list(Map<String, ?> params) { return c.request("GET", "/suppressions" + qs(params), null, null); }
        public Map<String, Object> create(Map<String, Object> body) { return c.request("POST", "/suppressions", body, null); }
        public Map<String, Object> remove(String id) { return c.request("DELETE", "/suppressions/" + enc(id), null, null); }
    }

    public static final class ContactProperties extends Crud {
        ContactProperties(Usend c) { super(c, "/contact-properties"); }

    }

    public static final class Events extends Crud {
        Events(Usend c) { super(c, "/events"); }
        public Map<String, Object> send(Map<String, Object> body) { return c.request("POST", "/events/send", body, null); }
    }

    public static final class Automations extends Crud {
        Automations(Usend c) { super(c, "/automations"); }
        public Map<String, Object> duplicate(String id) { return c.request("POST", "/automations/" + enc(id) + "/duplicate", null, null); }
        public Map<String, Object> stop(String id) { return c.request("POST", "/automations/" + enc(id) + "/stop", null, null); }
        public Map<String, Object> runs(String id) { return runs(id, null); }
        public Map<String, Object> runs(String id, Map<String, ?> params) { return c.request("GET", "/automations/" + enc(id) + "/runs" + qs(params), null, null); }
        public Map<String, Object> run(String id, String runId) { return c.request("GET", "/automations/" + enc(id) + "/runs/" + enc(runId), null, null); }
    }

    public static final class Inboxes extends Crud {
        Inboxes(Usend c) { super(c, "/inboxes"); }
        public Map<String, Object> createLabel(String id, Map<String, Object> body) { return c.request("POST", "/inboxes/" + enc(id) + "/labels", body, null); }
        public Map<String, Object> listLabels(String id) { return listLabels(id, null); }
        public Map<String, Object> listLabels(String id, Map<String, ?> params) { return c.request("GET", "/inboxes/" + enc(id) + "/labels" + qs(params), null, null); }
        public Map<String, Object> getLabel(String id, String labelId) { return c.request("GET", "/inboxes/" + enc(id) + "/labels/" + enc(labelId), null, null); }
        public Map<String, Object> updateLabel(String id, String labelId, Map<String, Object> body) { return c.request("PATCH", "/inboxes/" + enc(id) + "/labels/" + enc(labelId), body, null); }
        public Map<String, Object> deleteLabel(String id, String labelId) { return c.request("DELETE", "/inboxes/" + enc(id) + "/labels/" + enc(labelId), null, null); }
        public Map<String, Object> listThreads(String id) { return listThreads(id, null); }
        public Map<String, Object> listThreads(String id, Map<String, ?> params) { return c.request("GET", "/inboxes/" + enc(id) + "/threads" + qs(params), null, null); }
        public Map<String, Object> getThread(String id, String threadId) { return c.request("GET", "/inboxes/" + enc(id) + "/threads/" + enc(threadId), null, null); }
        public Map<String, Object> updateThread(String id, String threadId, Map<String, Object> body) { return c.request("PATCH", "/inboxes/" + enc(id) + "/threads/" + enc(threadId), body, null); }
        public Map<String, Object> deleteThread(String id, String threadId) { return c.request("DELETE", "/inboxes/" + enc(id) + "/threads/" + enc(threadId), null, null); }
        public Map<String, Object> listThreadEmails(String id, String threadId) { return listThreadEmails(id, threadId, null); }
        public Map<String, Object> listThreadEmails(String id, String threadId, Map<String, ?> params) { return c.request("GET", "/inboxes/" + enc(id) + "/threads/" + enc(threadId) + "/emails" + qs(params), null, null); }
        public Map<String, Object> getThreadEmail(String id, String threadId, String emailId) { return c.request("GET", "/inboxes/" + enc(id) + "/threads/" + enc(threadId) + "/emails/" + enc(emailId), null, null); }
        public Map<String, Object> reply(String id, String threadId, String emailId, Map<String, Object> body) { return c.request("POST", "/inboxes/" + enc(id) + "/threads/" + enc(threadId) + "/emails/" + enc(emailId) + "/reply", body, null); }
        public Map<String, Object> forward(String id, String threadId, String emailId, Map<String, Object> body) { return c.request("POST", "/inboxes/" + enc(id) + "/threads/" + enc(threadId) + "/emails/" + enc(emailId) + "/forward", body, null); }
        public Map<String, Object> createDraft(String id, Map<String, Object> body) { return c.request("POST", "/inboxes/" + enc(id) + "/drafts", body, null); }
        public Map<String, Object> listDrafts(String id) { return listDrafts(id, null); }
        public Map<String, Object> listDrafts(String id, Map<String, ?> params) { return c.request("GET", "/inboxes/" + enc(id) + "/drafts" + qs(params), null, null); }
        public Map<String, Object> getDraft(String id, String draftId) { return c.request("GET", "/inboxes/" + enc(id) + "/drafts/" + enc(draftId), null, null); }
        public Map<String, Object> updateDraft(String id, String draftId, Map<String, Object> body) { return c.request("PATCH", "/inboxes/" + enc(id) + "/drafts/" + enc(draftId), body, null); }
        public Map<String, Object> deleteDraft(String id, String draftId) { return c.request("DELETE", "/inboxes/" + enc(id) + "/drafts/" + enc(draftId), null, null); }
        public Map<String, Object> sendDraft(String id, String draftId) { return c.request("POST", "/inboxes/" + enc(id) + "/drafts/" + enc(draftId) + "/send", null, null); }
    }

    public static final class Logs extends Resource {
        Logs(Usend c) { super(c); }
        public Map<String, Object> list() { return list(null); }
        public Map<String, Object> list(Map<String, ?> params) { return c.request("GET", "/logs" + qs(params), null, null); }
        public Map<String, Object> get(String id) { return c.request("GET", "/logs/" + enc(id), null, null); }
        public Map<String, Object> userAgents() { return c.request("GET", "/logs/user-agents", null, null); }
    }

    public static final class Tools extends Resource {
        Tools(Usend c) { super(c); }
        public Map<String, Object> dmarcCheck(Map<String, Object> body) { return c.request("POST", "/tools/dmarc-check", body, null); }
    }

    public static final class OAuth extends Resource {
        OAuth(Usend c) { super(c); }
        public Map<String, Object> metadata() { return c.request("GET", "/.well-known/oauth-authorization-server", null, null); }
        public Map<String, Object> register(Map<String, Object> body) { return c.request("POST", "/oauth/register", body, null); }
        public Map<String, Object> token(Map<String, Object> body) { return c.request("POST", "/oauth/token", body, null); }
        public Map<String, Object> revoke(Map<String, Object> body) { return c.request("POST", "/oauth/revoke", body, null); }
    }
}
