package et.usend;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** JUnit-free tests: run {@code main}. Exits non-zero on failure. */
public final class UsendTest {
    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        testJson();
        testWebhook();
        testSmtp();
        testHttp();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static void testJson() {
        Object v = Json.parse("{\"a\":[1,2.5,-3e2,\"x\\n\\u00e9\\\"\"],\"b\":null,\"c\":true,\"d\":{}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        check(((List<?>) m.get("a")).get(0).equals(1L), "json long");
        check(((List<?>) m.get("a")).get(1).equals(2.5), "json double");
        check(((List<?>) m.get("a")).get(2).equals(-300.0), "json exp");
        check(((List<?>) m.get("a")).get(3).equals("x\né\""), "json string escapes");
        check(m.containsKey("b") && m.get("b") == null, "json null");
        check(Boolean.TRUE.equals(m.get("c")), "json true");
        String out = Json.stringify(m);
        check(Json.parse(out).equals(m), "json round trip: " + out);
        check(Json.stringify("a\"b\u0001").equals("\"a\\\"b\\u0001\""), "json quote control");
        boolean threw = false;
        try { Json.parse("{\"a\":"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "json malformed throws");
        threw = false;
        try { Json.parse("[1] x"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "json trailing throws");
    }

    static String sign(String secretB64, String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secretB64), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    static void testWebhook() throws Exception {
        String raw = Base64.getEncoder().encodeToString("super-secret-key-bytes".getBytes(StandardCharsets.UTF_8));
        String body = "{\"type\":\"email.delivered\"}";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Webhook-Id", "msg_1");
        h.put("Webhook-Timestamp", ts);
        h.put("Webhook-Signature", "v1,AAAA v1," + sign(raw, "msg_1", ts, body));
        Map<String, Object> ev = Webhook.verify(body, h, "whsec_" + raw);
        check("email.delivered".equals(ev.get("type")), "webhook verifies");

        h.put("Webhook-Signature", "v1," + sign(raw, "msg_1", ts, body + " "));
        check(failsWith(() -> Webhook.verify(body, h, "whsec_" + raw), "invalid_webhook"), "webhook bad signature");

        Map<String, String> old = new LinkedHashMap<>();
        String oldTs = String.valueOf(System.currentTimeMillis() / 1000 - 3600);
        old.put("webhook-id", "msg_1");
        old.put("webhook-timestamp", oldTs);
        old.put("webhook-signature", "v1," + sign(raw, "msg_1", oldTs, body));
        check(failsWith(() -> Webhook.verify(body, old, "whsec_" + raw), "invalid_webhook"), "webhook old timestamp");
        check(failsWith(() -> Webhook.verify(body, new LinkedHashMap<>(), "whsec_" + raw), "invalid_webhook"), "webhook missing headers");
    }

    static boolean failsWith(Runnable r, String name) {
        try {
            r.run();
            return false;
        } catch (UsendException e) {
            return name.equals(e.getErrorName());
        }
    }

    static void testSmtp() {
        SmtpConfig s = new Usend("us_k").smtpConfig();
        check(s.host().equals("smtp.usend.et") && s.port() == 465 && s.ssl() && !s.starttls(), "smtp defaults");
        check(s.username().equals("usend") && s.password().equals("us_k"), "smtp credentials");
        SmtpConfig s2 = new Usend("us_k", new Usend.Options().baseUrl("https://api.example.com/")).smtpConfig(587, null);
        check(s2.host().equals("smtp.example.com") && s2.starttls(), "smtp derived host");
        check(Usend.smtpConfig("us_k", 465, "mx.me", null).host().equals("mx.me"), "smtp host override");
        check(failsWith(() -> Usend.smtpConfig("us_k", 25, null, null), "invalid_port"), "smtp bad port");
    }

    static void testHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> seen = new ArrayList<>();
        AtomicInteger limited = new AtomicInteger();
        server.createContext("/", ex -> {
            String body = readAll(ex.getRequestBody());
            String path = ex.getRequestURI().toString();
            seen.add(ex.getRequestMethod() + " " + path + " | " + body + " | " + ex.getRequestHeaders().getFirst("Authorization")
                    + " | " + ex.getRequestHeaders().getFirst("Idempotency-Key"));
            int status = 200;
            String resp = "{\"id\":\"abc\"}";
            if (path.equals("/emails/events-list/events")) resp = "[{\"type\":\"delivered\"}]";
            if (path.equals("/domains/missing")) { status = 404; resp = "{\"statusCode\":404,\"name\":\"not_found\",\"message\":\"Domain not found.\"}"; }
            if (path.equals("/limited")) {
                if (limited.incrementAndGet() == 1) {
                    status = 429;
                    resp = "{\"statusCode\":429,\"name\":\"rate_limit_exceeded\",\"message\":\"slow down\"}";
                    ex.getResponseHeaders().add("retry-after", "0");
                }
            }
            if (path.equals("/events/send")) { status = 202; resp = "{\"object\":\"event\",\"id\":\"ev1\"}"; }
            if (path.endsWith("/raw")) resp = "From: a@b.com\r\n\r\nhi";
            if (path.equals("/oauth/token")) { status = 400; resp = "{\"error\":\"invalid_grant\",\"error_description\":\"bad code\"}"; }
            if (path.equals("/quota")) { status = 429; resp = "{\"statusCode\":429,\"name\":\"daily_quota_exceeded\",\"message\":\"quota\"}"; }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Usend u = new Usend("us_test", new Usend.Options().baseUrl(base).maxRetries(2));

            Map<String, Object> email = new LinkedHashMap<>();
            email.put("from", "a@b.com");
            email.put("to", List.of("c@d.com"));
            Map<String, Object> r = u.emails.send(email, "key-1");
            check("abc".equals(r.get("id")), "send returns json");
            check(seen.get(0).equals("POST /emails | {\"from\":\"a@b.com\",\"to\":[\"c@d.com\"]} | Bearer us_test | key-1"), "send request: " + seen.get(0));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("limit", 5);
            params.put("after", null);
            u.emails.list(params);
            check(seen.get(1).startsWith("GET /emails?limit=5 |"), "list query: " + seen.get(1));

            List<Object> events = u.emails.events("events-list");
            check(events.size() == 1, "events list");

            u.templates.get("a b/c");
            check(seen.get(3).startsWith("GET /templates/a%20b%2Fc |"), "path encoding: " + seen.get(3));

            try {
                u.domains.get("missing");
                check(false, "404 should throw");
            } catch (UsendException e) {
                check(e.getStatusCode() == 404 && e.getErrorName().equals("not_found") && e.getMessage().equals("Domain not found."), "error mapping");
            }

            int before = seen.size();
            Map<String, Object> viaRetry = u.request("GET", "/limited", null, null);
            check("abc".equals(viaRetry.get("id")) && seen.size() == before + 2, "429 rate limit retried");

            before = seen.size();
            try {
                u.request("GET", "/quota", null, null);
                check(false, "quota should throw");
            } catch (UsendException e) {
                check(e.getErrorName().equals("daily_quota_exceeded") && seen.size() == before + 1, "quota not retried");
            }

            Map<String, Object> evBody = new LinkedHashMap<>();
            evBody.put("event", "order.paid");
            evBody.put("email", "a@b.com");
            check("ev1".equals(u.events.send(evBody).get("id")), "events.send accepts 202");
            check(seen.get(seen.size() - 1).startsWith("POST /events/send | {\"event\":\"order.paid\""), "events.send request");

            u.broadcasts.recipients("b1", "clicked", Map.of("limit", 5));
            String rq = seen.get(seen.size() - 1);
            check(rq.startsWith("GET /broadcasts/b1/recipients?") && rq.contains("type=clicked") && rq.contains("limit=5"), "recipients sends type: " + rq);

            u.inboxes.sendDraft("i1", "d1");
            check(seen.get(seen.size() - 1).startsWith("POST /inboxes/i1/drafts/d1/send |"), "inboxes.sendDraft path");
            u.inboxes.listThreads("i1", Map.of("folder", "inbox"));
            check(seen.get(seen.size() - 1).startsWith("GET /inboxes/i1/threads?folder=inbox |"), "inboxes.listThreads query");
            u.automations.runs("a1");
            check(seen.get(seen.size() - 1).startsWith("GET /automations/a1/runs |"), "automations.runs path");
            u.webhooks.rotateSecret("w1");
            check(seen.get(seen.size() - 1).startsWith("POST /webhooks/w1/rotate-secret |"), "webhooks.rotateSecret");
            u.stats(Map.of("days", 7));
            check(seen.get(seen.size() - 1).startsWith("GET /stats?days=7 |"), "stats");

            byte[] raw = u.received.raw("e1");
            check(new String(raw, StandardCharsets.UTF_8).endsWith("hi"), "raw download");

            try {
                u.oauth.token(new LinkedHashMap<>());
                check(false, "oauth error should throw");
            } catch (UsendException e) {
                check(e.getErrorName().equals("invalid_grant") && e.getMessage().equals("bad code"), "oauth error shape");
            }

            Usend dead = new Usend("us_test", new Usend.Options().baseUrl("http://127.0.0.1:1").maxRetries(0));
            try {
                dead.emails.get("x");
                check(false, "network error should throw");
            } catch (UsendException e) {
                check(e.getStatusCode() == 0 && e.getErrorName().equals("application_error"), "network error mapping");
            }
        
            int slBase = seen.size();
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("target_url", "https://example.com");
            link.put("code", "abc");
            u.shortLinks.create(link);
            u.shortLinks.stats("l 1", 7);
            u.shortLinks.stats("l1", null);
            u.shortLinks.remove("l1");
            check(seen.get(slBase).startsWith("POST /short-links | {\"target_url\":\"https://example.com\",\"code\":\"abc\"}"), "short link create: " + seen.get(slBase));
            check(seen.get(slBase + 1).startsWith("GET /short-links/l%201/stats?days=7 |"), "short link stats: " + seen.get(slBase + 1));
            check(seen.get(slBase + 2).startsWith("GET /short-links/l1/stats |"), "short link stats default: " + seen.get(slBase + 2));
            check(seen.get(slBase + 3).startsWith("DELETE /short-links/l1 |"), "short link remove: " + seen.get(slBase + 3));
        } finally {
            server.stop(0);
        }
    }
}
