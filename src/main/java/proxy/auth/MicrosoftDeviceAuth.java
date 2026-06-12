package proxy.auth;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;

/**
 * Headless Microsoft authentication using the OAuth 2.0 <b>device-code</b> flow. Unlike
 * {@link MicrosoftAuthServer} (which binds a localhost port and opens a browser), this needs no
 * browser or inbound port on the machine running the downloader: a short code is printed and the
 * user approves it on any other device. That makes it the flow to use in Docker / headless servers.
 *
 * <p>It mirrors the flow the web console uses ({@code web/auth.py}): the legacy live.com client with
 * the {@code MBI_SSL} scope, an RPS ticket prefixed {@code t=}, then the Xbox Live → XSTS → Minecraft
 * Services token chain. The Microsoft refresh token is cached to disk so later launches are silent.
 *
 * <p>Endpoints and the client id are all overridable via environment variables (no hard-coded values
 * that can't be changed): {@code MS_CLIENT_ID}, {@code MS_SCOPE}, {@code MS_DEVICE_CODE_URL},
 * {@code MS_TOKEN_URL}.
 */
public class MicrosoftDeviceAuth {
    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    // Defaults match the public Minecraft-launcher legacy login.live.com client (device-flow capable).
    private static final String CLIENT_ID = env("MS_CLIENT_ID", "00000000402b5328");
    private static final String SCOPE = env("MS_SCOPE", "service::user.auth.xboxlive.com::MBI_SSL");
    private static final String DEVICE_CODE_URL = env("MS_DEVICE_CODE_URL", "https://login.live.com/oauth20_connect.srf");
    private static final String TOKEN_URL = env("MS_TOKEN_URL", "https://login.live.com/oauth20_token.srf");

    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    /** Details the caller needs to show the user so they can approve the sign-in. */
    public static class DeviceCodeInfo {
        public final String userCode;
        public final String verificationUri;
        public final String message;
        public final int interval;
        public final int expiresIn;

        DeviceCodeInfo(String userCode, String verificationUri, String message, int interval, int expiresIn) {
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.message = message;
            this.interval = interval;
            this.expiresIn = expiresIn;
        }
    }

    /** Minimal on-disk cache: just the Microsoft refresh token so we never store the long-lived MC token. */
    private static class TokenCache {
        String refreshToken;
    }

    private final Path cacheFile;

    public MicrosoftDeviceAuth(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    /**
     * Obtain valid Minecraft auth details. If a cached refresh token exists and still works, this
     * returns silently (no user interaction). Otherwise {@code onCode} is invoked with the one-time
     * code/URL the user must approve, and we poll until they do (or the code expires).
     *
     * @param onCode called once with the code to display, only when interactive sign-in is required.
     */
    public AuthDetails authenticate(Consumer<DeviceCodeInfo> onCode) throws IOException {
        TokenCache cache = loadCache();
        if (cache != null && cache.refreshToken != null && !cache.refreshToken.isBlank()) {
            try {
                String msAccess = refreshMicrosoftToken(cache.refreshToken);
                return msToMinecraft(msAccess);
            } catch (RuntimeException ex) {
                System.out.println("[ms-auth] cached Microsoft session expired; interactive sign-in required.");
            }
        }
        return interactiveAuthenticate(onCode);
    }

    private AuthDetails interactiveAuthenticate(Consumer<DeviceCodeInfo> onCode) throws IOException {
        HttpResponse<JsonNode> res = Unirest.post(DEVICE_CODE_URL)
            .contentType("application/x-www-form-urlencoded")
            .field("client_id", CLIENT_ID)
            .field("scope", SCOPE)
            .field("response_type", "device_code")
            .asJson();
        if (!res.isSuccess()) {
            throw new MinecraftAuthenticationException("Cannot start Microsoft device-code flow. Status: " + res.getStatus());
        }
        JSONObject jso = res.getBody().getObject();
        String deviceCode = jso.getString("device_code");
        int interval = jso.optInt("interval", 5);
        int expiresIn = jso.optInt("expires_in", 900);
        onCode.accept(new DeviceCodeInfo(
            jso.getString("user_code"),
            jso.optString("verification_uri", "https://www.microsoft.com/link"),
            jso.optString("message", ""),
            interval, expiresIn));

        LocalDateTime deadline = LocalDateTime.now().plusSeconds(expiresIn);
        while (LocalDateTime.now().isBefore(deadline)) {
            sleep(interval);
            HttpResponse<JsonNode> poll = Unirest.post(TOKEN_URL)
                .contentType("application/x-www-form-urlencoded")
                .field("client_id", CLIENT_ID)
                .field("grant_type", DEVICE_GRANT)
                .field("device_code", deviceCode)
                .asJson();
            JSONObject body = poll.getBody().getObject();
            if (poll.isSuccess()) {
                String msAccess = body.getString("access_token");
                saveCache(body.optString("refresh_token", null));
                return msToMinecraft(msAccess);
            }
            String err = body.optString("error", "");
            if (err.equals("authorization_pending")) {
                continue;
            }
            if (err.equals("slow_down")) {
                interval += 5;
                continue;
            }
            throw new MinecraftAuthenticationException("Microsoft sign-in failed: "
                + body.optString("error_description", err));
        }
        throw new MinecraftAuthenticationException("Microsoft sign-in timed out before it was approved.");
    }

    private String refreshMicrosoftToken(String refreshToken) {
        HttpResponse<JsonNode> res = Unirest.post(TOKEN_URL)
            .contentType("application/x-www-form-urlencoded")
            .field("client_id", CLIENT_ID)
            .field("scope", SCOPE)
            .field("grant_type", "refresh_token")
            .field("refresh_token", refreshToken)
            .asJson();
        if (!res.isSuccess()) {
            throw new MinecraftAuthenticationException("Cannot refresh Microsoft token. Status: " + res.getStatus());
        }
        JSONObject jso = res.getBody().getObject();
        // Microsoft rotates refresh tokens; persist the new one so the cache keeps working.
        saveCache(jso.optString("refresh_token", refreshToken));
        return jso.getString("access_token");
    }

    /** Run the Xbox Live → XSTS → Minecraft Services chain and resolve the profile. */
    private AuthDetails msToMinecraft(String msAccessToken) {
        HttpResponse<JsonNode> xbl = Unirest.post(XBL_URL)
            .contentType("application/json").accept("application/json")
            .body(new Gson().toJson(new XblBody(msAccessToken)))
            .asJson();
        if (!xbl.isSuccess()) {
            throw new MinecraftAuthenticationException("Cannot get Xbox Live token. Status: " + xbl.getStatus());
        }
        JSONObject xblJson = xbl.getBody().getObject();
        String xblToken = xblJson.getString("Token");
        String userHash = xblJson.getJSONObject("DisplayClaims").getJSONArray("xui")
            .getJSONObject(0).getString("uhs");

        HttpResponse<JsonNode> xsts = Unirest.post(XSTS_URL)
            .contentType("application/json").accept("application/json")
            .body(new Gson().toJson(new XstsBody(xblToken)))
            .asJson();
        if (!xsts.isSuccess()) {
            throw new MinecraftAuthenticationException("Cannot get XSTS token. Status: " + xsts.getStatus());
        }
        String xstsToken = xsts.getBody().getObject().getString("Token");

        HttpResponse<JsonNode> mc = Unirest.post(MC_LOGIN_URL)
            .contentType("application/json").accept("application/json")
            .body(new Gson().toJson(new McBody(userHash, xstsToken)))
            .asJson();
        if (!mc.isSuccess()) {
            throw new MinecraftAuthenticationException("Cannot get Minecraft token. Status: " + mc.getStatus());
        }
        String mcToken = mc.getBody().getObject().getString("access_token");

        // Derives username + UUID from the token via the profile API (throws if no Java Edition license).
        return AuthDetails.fromAccessToken(mcToken);
    }

    private TokenCache loadCache() {
        try {
            if (cacheFile != null && Files.isReadable(cacheFile)) {
                String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
                return new Gson().fromJson(json, TokenCache.class);
            }
        } catch (IOException | RuntimeException ex) {
            // corrupt/unreadable cache just means we sign in again
        }
        return null;
    }

    private void saveCache(String refreshToken) {
        if (cacheFile == null || refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            TokenCache cache = new TokenCache();
            cache.refreshToken = refreshToken;
            Files.writeString(cacheFile, new Gson().toJson(cache), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.err.println("[ms-auth] WARNING: could not persist the Microsoft session to "
                + cacheFile + " (will re-authenticate next time): " + ex.getMessage());
        }
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- request bodies (serialized to JSON; field names must match the Xbox/MC API exactly) ----
    private static class XblBody {
        final String RelyingParty = "http://auth.xboxlive.com";
        final String TokenType = "JWT";
        final Properties Properties;

        XblBody(String msAccessToken) {
            this.Properties = new Properties(msAccessToken);
        }

        private static class Properties {
            final String AuthMethod = "RPS";
            final String SiteName = "user.auth.xboxlive.com";
            final String RpsTicket;

            Properties(String msAccessToken) {
                // legacy live.com tokens use the "t=" prefix (vs "d=" for Azure-AD auth-code tokens).
                this.RpsTicket = "t=" + msAccessToken;
            }
        }
    }

    private static class XstsBody {
        final String RelyingParty = "rp://api.minecraftservices.com/";
        final String TokenType = "JWT";
        final Properties Properties;

        XstsBody(String xblToken) {
            this.Properties = new Properties(xblToken);
        }

        private static class Properties {
            final String SandboxId = "RETAIL";
            final String[] UserTokens;

            Properties(String xblToken) {
                this.UserTokens = new String[] { xblToken };
            }
        }
    }

    private static class McBody {
        final String identityToken;

        McBody(String userHash, String xstsToken) {
            this.identityToken = "XBL3.0 x=" + userHash + ";" + xstsToken;
        }
    }
}
