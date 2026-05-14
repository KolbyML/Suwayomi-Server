package android.webkit;

import android.annotation.Nullable;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@SuppressWarnings("DEPRECATION")
public class McCookieManager extends CookieManager {
    private static final String TAG = "McCookieManager";

    private static volatile boolean nativeBridgeAvailable;

    static {
        boolean available;
        try {
            // Probe the native method to detect if the JNI binding exists.
            // On iOS/Android the native library is loaded before the JVM
            // starts, so this succeeds.  On desktop (macOS/Linux/Windows)
            // no native library provides the symbol and the call throws
            // UnsatisfiedLinkError, which we catch once here so that every
            // subsequent cookie operation falls back to the no-op path
            // instead of crashing the OkHttp interceptor chain.
            nativeGetCookie0("http://probe.invalid".getBytes(StandardCharsets.UTF_8));
            available = true;
        } catch (UnsatisfiedLinkError e) {
            available = false;
            Log.w(TAG, "Native cookie bridge not linked, disabling McCookieManager", e);
        }
        nativeBridgeAvailable = available;
    }

    private boolean acceptCookie = true;
    private boolean acceptThirdPartyCookies = true;
    private boolean allowFileSchemeCookies = false;

    private static boolean isNativeCookieEnabled() {
        if (!nativeBridgeAvailable) {
            return false;
        }
        String propertyValue = System.getProperty("suwayomi.native.cookie");
        if (propertyValue == null) {
            propertyValue = System.getenv("SUWAYOMI_NATIVE_COOKIE");
        }
        return propertyValue != null && Boolean.parseBoolean(propertyValue);
    }

    private static void disableNativeBridge(UnsatisfiedLinkError error) {
        nativeBridgeAvailable = false;
        Log.w(TAG, "Native cookie bridge unavailable, falling back to no-op cookie storage", error);
    }

    public static boolean isNativeBridgeAvailable() {
        return nativeBridgeAvailable;
    }

    @Override
    public void setAcceptCookie(boolean accept) {
        acceptCookie = accept;
    }

    @Override
    public boolean acceptCookie() {
        return acceptCookie;
    }

    @Override
    public void setAcceptThirdPartyCookies(WebView webview, boolean accept) {
        acceptThirdPartyCookies = accept;
    }

    @Override
    public boolean acceptThirdPartyCookies(WebView webview) {
        return acceptThirdPartyCookies;
    }

    @Override
    public void setCookie(String url, String value) {
        if (!acceptCookie || !isNativeCookieEnabled()) {
            return;
        }
        if (url == null || value == null || value.isEmpty()) {
            return;
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
            url = "http://" + url;
        }
        try {
            nativeSetCookie0(stringToUtf8ByteArray(url), stringToUtf8ByteArray(value));
        } catch (UnsatisfiedLinkError e) {
            disableNativeBridge(e);
        }
    }

    @Override
    public void setCookie(String url, String value, @Nullable ValueCallback<Boolean> callback) {
        setCookie(url, value);
        if (callback != null) {
            callback.onReceiveValue(true);
        }
    }

    @Override
    public String getCookie(String url) {
        if (!acceptCookie || !isNativeCookieEnabled()) {
            return "";
        }
        if (url == null) {
            return null;
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
            url = "http://" + url;
        }
        try {
            byte[] bytes = nativeGetCookie0(stringToUtf8ByteArray(url));
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (UnsatisfiedLinkError e) {
            disableNativeBridge(e);
            return null;
        }
    }

    @Deprecated
    @Override
    public void removeSessionCookie() {}

    @Override
    public void removeSessionCookies(@Nullable ValueCallback<Boolean> callback) {
        if (callback != null) {
            callback.onReceiveValue(false);
        }
    }

    @Deprecated
    @Override
    public void removeAllCookie() {}

    @Override
    public void removeAllCookies(@Nullable ValueCallback<Boolean> callback) {
        if (callback != null) {
            callback.onReceiveValue(false);
        }
    }

    @Override
    public boolean hasCookies() {
        return acceptCookie;
    }

    @Deprecated
    @Override
    public void removeExpiredCookie() {}

    @Override
    public void flush() {}

    @Override
    public boolean allowFileSchemeCookiesImpl() {
        return allowFileSchemeCookies;
    }

    @Override
    public void setAcceptFileSchemeCookiesImpl(boolean accept) {
        allowFileSchemeCookies = accept;
    }

    private static byte[] stringToUtf8ByteArray(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static native byte[] nativeGetCookie0(byte[] urlUtf8);
    private static native void nativeSetCookie0(byte[] urlUtf8, byte[] valueUtf8);
}
