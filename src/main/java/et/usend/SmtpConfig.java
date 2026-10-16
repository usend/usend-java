package et.usend;

import java.util.LinkedHashMap;
import java.util.Map;

/** SMTP settings for sending through usend. Port 465 is implicit TLS, 587 is STARTTLS. */
public final class SmtpConfig {
    private final String host;
    private final int port;
    private final String password;

    SmtpConfig(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    public String host() { return host; }
    public int port() { return port; }
    public String username() { return "usend"; }
    /** The API key. */
    public String password() { return password; }
    /** True on port 465 (implicit TLS). */
    public boolean ssl() { return port == 465; }
    /** True on port 587 (STARTTLS). */
    public boolean starttls() { return port == 587; }

    /**
     * Jakarta Mail / JavaMail properties for {@code Session.getInstance}. Authenticate with
     * {@link #username()} and {@link #password()}.
     */
    public Map<String, String> toMailProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.auth", "true");
        if (ssl()) {
            p.put("mail.smtp.ssl.enable", "true");
        } else {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        return p;
    }

    @Override
    public String toString() {
        return "SmtpConfig{host=" + host + ", port=" + port + ", username=usend, password=***}";
    }
}
