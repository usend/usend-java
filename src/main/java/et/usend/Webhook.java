package et.usend;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies usend webhook deliveries (Standard Webhooks signatures). */
public final class Webhook {
    private Webhook() {}

    /** Verifies with the default five minute tolerance and returns the parsed event. */
    public static Map<String, Object> verify(String payload, Map<String, String> headers, String secret) {
        return verify(payload, headers, secret, 300);
    }

    /**
     * Verifies a delivery and returns the parsed event.
     *
     * @param payload the raw request body, exactly as received
     * @param headers the request headers (names are matched case-insensitively)
     * @param secret the endpoint's signing secret ({@code whsec_...})
     * @param toleranceSeconds how far the timestamp may be from now
     * @throws UsendException with name {@code invalid_webhook} if verification fails
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> verify(String payload, Map<String, String> headers, String secret, long toleranceSeconds) {
        String id = null, ts = null, sigs = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            if (k.equals("webhook-id")) id = e.getValue();
            else if (k.equals("webhook-timestamp")) ts = e.getValue();
            else if (k.equals("webhook-signature")) sigs = e.getValue();
        }
        if (id == null || ts == null || sigs == null) {
            throw invalid("Missing webhook-id, webhook-timestamp or webhook-signature header");
        }
        long t;
        try {
            t = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            throw invalid("Invalid webhook timestamp");
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - t) > toleranceSeconds) {
            throw invalid("Webhook timestamp is too old or too new");
        }
        byte[] expected;
        try {
            String s = secret.startsWith("whsec_") ? secret.substring(6) : secret;
            byte[] key = Base64.getDecoder().decode(s);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String signed = id + "." + ts + "." + payload;
            expected = Base64.getEncoder().encode(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw invalid("Invalid webhook secret");
        }
        boolean ok = false;
        for (String part : sigs.trim().split(" ")) {
            int comma = part.indexOf(',');
            if (comma < 0) continue;
            if (part.substring(0, comma).equals("v1")) {
                byte[] got = part.substring(comma + 1).getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(got, expected)) ok = true;
            }
        }
        if (!ok) throw invalid("Invalid webhook signature");
        try {
            return (Map<String, Object>) Json.parse(payload);
        } catch (IllegalArgumentException | ClassCastException e) {
            throw invalid("Webhook payload is not a JSON object");
        }
    }

    private static UsendException invalid(String msg) {
        return new UsendException(msg, "invalid_webhook", 0);
    }
}
