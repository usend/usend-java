# usend for Java

The official Java SDK for [usend](https://usend.et), the email API for developers. Java 11 or newer, no dependencies
(it uses `java.net.http.HttpClient` and a small built-in JSON reader and writer).

## Install

```xml
<dependency>
  <groupId>et.usend</groupId>
  <artifactId>usend</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle: `implementation("et.usend:usend:1.0.0")`.

## Quick start

```java
import et.usend.Usend;
import java.util.List;
import java.util.Map;

Usend usend = new Usend(System.getenv("USEND_API_KEY"));

Map<String, Object> sent = usend.emails.send(Map.of(
    "from", "Acme <hello@acme.com>",
    "to", List.of("ann@example.com"),
    "subject", "Your login code",
    "html", "<p>Your code is <b>482913</b></p>"));

System.out.println(sent.get("id"));
```

Requests and responses are plain `Map<String, Object>` and `List<Object>` (numbers are `Long` or `Double`). `et.usend.Json`
is the built-in parser if you need it directly.

- The key defaults to `USEND_API_KEY`; the base URL to `USEND_BASE_URL` or `https://api.usend.et`:
  `new Usend(key, new Usend.Options().baseUrl("https://api.example.com").timeout(Duration.ofSeconds(10)).maxRetries(3))`.
- Resources: `emails`, `batch`, `received`, `templates`, `domains`, `apiKeys`, `segments`, `contacts`, `topics`,
  `broadcasts`, `webhooks`, `suppressions`. List calls take an optional `Map` of query parameters
  (`usend.emails.list(Map.of("limit", 20))`). Email events: `usend.emails.events(id)`.
- Idempotency: `usend.emails.send(email, Usend.newIdempotencyKey())`. A repeated key returns the first result.
- Retries: `429 rate_limit_exceeded` is retried honouring `retry-after`; idempotent requests (GET, DELETE, or a POST
  with an idempotency key) are also retried on 5xx and network errors. Quota errors are never retried.

## SMTP

The API key is the SMTP password (host `smtp.usend.et`, port 465 implicit TLS or 587 STARTTLS, username `usend`).

```java
SmtpConfig smtp = usend.smtpConfig();          // or usend.smtpConfig(587, null)
Properties props = new Properties();
props.putAll(smtp.toMailProperties());          // for Jakarta Mail / JavaMail
Session session = Session.getInstance(props, new Authenticator() {
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(smtp.username(), smtp.password());
    }
});
```

For a self-hosted platform the host comes from `USEND_SMTP_HOST`, an explicit `host`, or the base URL
(`https://api.x.com` gives `smtp.x.com`).

## Webhooks

Verify a delivery with the raw request body and headers; it returns the parsed event or throws.

```java
Map<String, Object> event = Webhook.verify(rawBody, headers, System.getenv("USEND_WEBHOOK_SECRET"));
```

Header names are matched case-insensitively; the default timestamp tolerance is 5 minutes.

## Errors

Failures throw `UsendException` (unchecked) with `getStatusCode()` (0 for network errors), `getErrorName()` (the API's
`name`, such as `validation_error`, `not_found`, `rate_limit_exceeded`; `application_error` for network failures;
`invalid_webhook` for bad signatures) and `getMessage()`.

```java
try {
    usend.domains.get(id);
} catch (UsendException e) {
    if (e.getStatusCode() == 404) { /* ... */ }
}
```

## More resources

`events` (custom events, `send` returns 202), `automations` (`duplicate`, `stop`, `runs`, `run`), `inboxes` (labels,
threads, drafts, `reply`, `forward`), `contactProperties`, `logs`, `tools.dmarcCheck`, `oauth` (metadata, register,
token, revoke; an OAuth access token `usa_...` can be passed as the key), plus extras on the resources above: email
`metrics`/`share`/`test`/`insights`, domain `createClaim`/`verifyClaim`/`insights`, template `versions`/`restoreVersion`,
segment `update`/`contacts`/`metrics`, contact `stats`/`importContacts`/`createImport`/`listImports`, broadcast
`cancel`/`duplicate`/`performance`/`clickedLinks`, webhook `listEvents`/`replayEvent`/`rotateSecret`, suppression
`batchCreate`/`batchRemove`, and `usend.stats()`.

`usend.broadcasts.recipients(id, type)` requires a `type` (sent, delivered, opened, clicked, bounced, complained,
unsubscribed, suppressed) and returns rows `{id, contact_id, email, count?, bounce_type?, clicked_links?}`.
`usend.received.raw(id)` and `usend.received.attachment(id, attachmentId)` return `byte[]`. Downloads are not retried.

Not covered: session-only endpoints (organization, billing, members), the `/audiences` routes and `/emails/receiving`
aliases. Docs: https://usend.et/docs
