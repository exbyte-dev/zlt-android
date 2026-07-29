# Play Store Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship ZLT Companion v1 to the Google Play Store, including a demo mode so a reviewer with no router can see the app work.

**Architecture:** Identity moves to a domain the developer owns before anything is published, because `applicationId` is permanent. Demo mode plugs into the `baseClient: OkHttpClient` parameter `ZltClient` already exposes, as a canned-response `Interceptor`, so every layer above it — login handshake, flat-JSON parsing, `RouterRepository`, `RouterViewModel`, the screens, the tile — runs unmodified against fixtures. Store assets are generated from the app's own icon and theme.

**Tech Stack:** Kotlin 2.1, AGP 8.10.1, Jetpack Compose (BOM 2024.12.01), OkHttp 4.12, DataStore Preferences, kotlinx-serialization-json, JUnit 4, MockWebServer.

## Global Constraints

- `applicationId` is `dev.exbyte.zltcompanion`. Permanent once published. The `namespace` stays `dev.zlt.companion` — compile-time only, never leaves the binary.
- `compileSdk` = 36, `targetSdk` = 36, `minSdk` = 26. Play requires target 36 for submissions from 31 Aug 2026.
- Store title: `Router Signal — for ZLT T10D`. In-app `app_name`: `Router Signal`.
- Every listing surface carries: not affiliated with, endorsed by, or supported by ZTE, MTN, or any carrier.
- Release artifact is an AAB via `bundleRelease`, never an APK.
- `versionCode` 1, `versionName` "1.0" for first submission. Increment `versionCode` by 1 per upload thereafter.
- No new dependencies. No analytics, crash reporting or telemetry — this is a stated privacy claim, not a preference.
- All 63 existing JVM unit tests stay green throughout.
- The SDK is user-local at `~/Android/Sdk`, set via `local.properties` (`sdk.dir`). `/opt/android-sdk` is root-owned and must not be written to.
- Demo readings must never be written to `StatusCache` — the Quick Settings tile must not report fictional signal.

---

### Task 1: Move identity to an owned namespace

**Files:**
- Modify: `app/build.gradle.kts:26` (`applicationId`)
- Modify: `app/src/main/res/values/strings.xml:2` (`app_name`)

**Interfaces:**
- Consumes: nothing.
- Produces: `applicationId` `dev.exbyte.zltcompanion`; string resource `app_name` = `Router Signal`.

- [ ] **Step 1: Change the applicationId**

In `app/build.gradle.kts`, inside `defaultConfig`:

```kotlin
    defaultConfig {
        applicationId = "dev.exbyte.zltcompanion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
```

Leave `namespace = "dev.zlt.companion"` on line 21 untouched. `targetSdk` is bumped in Task 2, not here.

- [ ] **Step 2: Change the app name**

In `app/src/main/res/values/strings.xml`, replace line 2:

```xml
    <string name="app_name">Router Signal</string>
```

`device_name` on line 5 stays `ZLT T10D MAX` — it is used in body copy ("Enter the address and password for your %1$s"), where naming the model is descriptive and correct.

- [ ] **Step 3: Verify the project still assembles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. The debug variant's applicationId is now `dev.exbyte.zltcompanion.debug`.

- [ ] **Step 4: Verify the tests still pass**

Run: `./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 63 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/res/values/strings.xml
git commit -m "Move the applicationId to an owned namespace and rename the app"
```

---

### Task 2: Target SDK 36 and produce an AAB

**Files:**
- Modify: `app/build.gradle.kts:22` (`compileSdk`), `app/build.gradle.kts:28` (`targetSdk`)
- Create: `local.properties` (gitignored)

**Interfaces:**
- Consumes: Task 1's `applicationId`.
- Produces: a release AAB at `app/build/outputs/bundle/release/app-release.aab`.

- [ ] **Step 1: Point the build at the user-local SDK**

Create `local.properties` in the repo root (already gitignored — do not commit it):

```properties
sdk.dir=/home/exbyte/Android/Sdk
```

- [ ] **Step 2: Confirm the required SDK packages are present**

Run: `ls ~/Android/Sdk/platforms ~/Android/Sdk/build-tools`
Expected: `android-36` under `platforms`, `36.0.0` under `build-tools`. If either is missing:

```bash
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root="$HOME/Android/Sdk" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

- [ ] **Step 3: Bump compileSdk and targetSdk**

In `app/build.gradle.kts`:

```kotlin
android {
    namespace = "dev.zlt.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.exbyte.zltcompanion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
```

- [ ] **Step 4: Build and run the tests against SDK 36**

Run: `./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 63 tests, 0 failures. A failure here means an API removed in 36 is in use — read the compiler error, do not suppress it.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "Target SDK 36 for the Play submission deadline"
```

---

### Task 3: DemoTransport — canned router responses

**Files:**
- Create: `app/src/main/kotlin/dev/zlt/companion/net/DemoTransport.kt`
- Test: `app/src/test/kotlin/dev/zlt/companion/net/DemoTransportTest.kt`

**Interfaces:**
- Consumes: `ZltProtocol.PROC_GET`, `ZltProtocol.PROC_POST`, `ZltProtocol.GOFORM_LOGIN`, `ZltProtocol.GOFORM_SET_BEARER`, `ZltProtocol.RESULT_SUCCESS`, `NetworkMode.value`.
- Produces:
  - `class DemoTransport : okhttp3.Interceptor`
  - `DemoTransport.okHttpClient(): OkHttpClient` — a client carrying this interceptor
  - `DemoTransport.CREDENTIALS: RouterCredentials` — the fixed credentials demo mode uses

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/zlt/companion/net/DemoTransportTest.kt`:

```kotlin
package dev.zlt.companion.net

import dev.zlt.companion.data.RouterRepository
import dev.zlt.companion.data.SignalLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo mode has to satisfy the same contract the real client does. If it stops
 * doing so, that is a failing test here rather than a Play review rejection.
 */
class DemoTransportTest {

    private fun repository() = RouterRepository(
        credentialsProvider = { DemoTransport.CREDENTIALS },
        clientFactory = { ZltClient(it, baseClient = DemoTransport.okHttpClient()) },
    )

    @Test
    fun `snapshot returns a full authenticated reading`() = runTest {
        val snapshot = repository().snapshot()

        assertTrue("demo should log in and reach the authed keys", snapshot.status.authed)
        assertEquals("LTE", snapshot.status.networkType)
        assertTrue(snapshot.status.hasService)

        val reading = snapshot.status.reading
        assertNotNull("a demo reading should resolve", reading)
        assertEquals("RSRP", reading!!.metric.label)
        assertEquals(-78, reading.dbm)
        assertEquals(SignalLevel.EXCELLENT, reading.level)
    }

    @Test
    fun `snapshot reports the configured mode`() = runTest {
        val snapshot = repository().snapshot()
        assertEquals(NetworkMode.LTE_ONLY, snapshot.mode?.configured)
    }

    @Test
    fun `setMode changes what the next snapshot reports`() = runTest {
        val repository = repository()

        val applied = repository.setMode(NetworkMode.AUTO)
        assertEquals(NetworkMode.AUTO, applied.configured)

        assertEquals(NetworkMode.AUTO, repository.snapshot().mode?.configured)
    }

    @Test
    fun `each transport starts from the same fixture`() = runTest {
        repository().setMode(NetworkMode.GSM_ONLY)
        // A fresh transport must not inherit the previous one's mutation.
        assertEquals(NetworkMode.LTE_ONLY, repository().snapshot().mode?.configured)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*DemoTransportTest*'`
Expected: FAIL — `Unresolved reference: DemoTransport`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/dev/zlt/companion/net/DemoTransport.kt`:

```kotlin
package dev.zlt.companion.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder

/**
 * A stand-in router, served entirely in-process.
 *
 * This exists so the app can be seen working without the hardware — by anyone
 * evaluating it, and by whoever reviews it for the Play Store, who will not
 * have a ZLT T10D MAX on their desk.
 *
 * It plugs into the `baseClient` parameter [ZltClient] already takes, so it
 * answers at the HTTP layer and nothing above it changes: the login handshake,
 * the flat-JSON parsing, [dev.zlt.companion.data.RouterRepository] and the
 * whole UI run exactly as they do against a real device. A fake further up
 * would skip the protocol layer and drift from it.
 *
 * State is per-instance, so one demo session cannot leak into the next.
 */
class DemoTransport : Interceptor {

    /** proc_get key -> value. Mutable so a mode change visibly takes effect. */
    private val values: MutableMap<String, String> = linkedMapOf(
        "network_type" to "LTE",
        "rssi" to "-58",
        "signalbar" to "4",
        "lte_rsrq" to "-9",
        "lte_pci" to "204",
        // Spelled out rather than imported from ConnectionState, following the
        // convention RouterMock states: these are wire values, and a renamed
        // constant should break a test rather than move both sides at once.
        "ppp_status" to "ppp_connected",
        "lte_rsrp" to "-78",
        "lte_band" to "3",
        "lte_snr" to "14",
        "net_select" to NetworkMode.LTE_ONLY.value,
        "current_network_mode" to "LTE",
        // Empty on this hardware, as on the real device. Present so the
        // repository's "did the device drop the mode keys?" check sees them.
        "net_select_mode" to "",
        "m_netselect_save" to "",
    )

    /**
     * Whether the demo session has logged in. Held here rather than in a cookie
     * so the login handshake is still exercised without cookie plumbing.
     */
    private var loggedIn = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = if (request.method == "POST") handlePost(request) else handleGet(request.url)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(JSON))
            .build()
    }

    private fun handleGet(url: HttpUrl): String {
        // OkHttp decodes %2C back to a comma for us.
        val cmds = url.queryParameter("cmd").orEmpty().split(',').filter { it.isNotEmpty() }
        return when {
            "get_token" in cmds -> json("token" to if (loggedIn) DEMO_TOKEN else "")
            "get_random_login" in cmds -> json("random_login" to DEMO_NONCE)
            "psw_fail_num_str" in cmds -> json(
                "psw_fail_num_str" to ZltProtocol.MAX_LOGIN_COUNT.toString(),
                "login_lock_time" to ZltProtocol.DEFAULT_LOCK_TIME.toString(),
            )
            // Authed keys read back empty until logged in, exactly as the
            // device does it, so the repository's fallback path stays real.
            else -> json(*cmds.map { it to visibleValue(it) }.toTypedArray())
        }
    }

    private fun visibleValue(key: String): String = when {
        key in ZltProtocol.AUTHED_KEYS && !loggedIn -> ""
        else -> values[key].orEmpty()
    }

    private fun handlePost(request: Request): String {
        val fields = formFields(request)
        return when (fields["goformId"]) {
            ZltProtocol.GOFORM_LOGIN -> {
                loggedIn = true
                json("result" to "0")
            }
            ZltProtocol.GOFORM_SET_BEARER -> {
                val requested = fields["BearerPreference"].orEmpty()
                if (NetworkMode.fromValue(requested) == null) {
                    json("result" to "failure")
                } else {
                    values["net_select"] = requested
                    json("result" to ZltProtocol.RESULT_SUCCESS)
                }
            }
            else -> json("result" to ZltProtocol.RESULT_SUCCESS)
        }
    }

    private fun formFields(request: Request): Map<String, String> {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(name) to decode(value)
            }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    /** The device answers a flat object of string values; so does this. */
    private fun json(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":\"${escape(value)}\""
        }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val DEMO_TOKEN = "demo-csrf-token"
        private const val DEMO_NONCE = "demo-nonce"

        /**
         * Demo mode never reaches the network, but [ZltClient] still builds URLs
         * from these, so the host has to pass [HostValidation].
         */
        val CREDENTIALS = RouterCredentials(
            host = "192.168.0.1",
            username = "admin",
            password = "demo",
        )

        /** A client whose every request is answered in-process. */
        fun okHttpClient(): OkHttpClient =
            OkHttpClient.Builder().addInterceptor(DemoTransport()).build()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*DemoTransportTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 67 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/zlt/companion/net/DemoTransport.kt \
        app/src/test/kotlin/dev/zlt/companion/net/DemoTransportTest.kt
git commit -m "Add a demo transport so the app runs without the hardware"
```

---

### Task 4: Persist the demo flag

**Files:**
- Modify: `app/src/main/kotlin/dev/zlt/companion/data/SettingsStore.kt`
- Test: `app/src/test/kotlin/dev/zlt/companion/data/RouterSettingsTest.kt` (create)

**Interfaces:**
- Consumes: `RouterSettings`, `SettingsStore` from Task 3's unchanged surroundings.
- Produces:
  - `RouterSettings.demoMode: Boolean` (defaults `false`)
  - `RouterSettings.isConfigured` returns `true` when `demoMode` is on
  - `SettingsStore.setDemoMode(enabled: Boolean)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/zlt/companion/data/RouterSettingsTest.kt`:

```kotlin
package dev.zlt.companion.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterSettingsTest {

    @Test
    fun `not configured without a password`() {
        assertFalse(RouterSettings().isConfigured)
    }

    @Test
    fun `configured with a valid host and a password`() {
        assertTrue(RouterSettings(host = "192.168.0.1", password = "secret").isConfigured)
    }

    @Test
    fun `demo mode counts as configured without any password`() {
        assertTrue(RouterSettings(password = "", demoMode = true).isConfigured)
    }

    @Test
    fun `demo mode is off by default`() {
        assertFalse(RouterSettings().demoMode)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*RouterSettingsTest*'`
Expected: FAIL — `No value passed for parameter 'demoMode'` / unresolved reference.

- [ ] **Step 3: Add the field and the setter**

In `app/src/main/kotlin/dev/zlt/companion/data/SettingsStore.kt`, add `demoMode` to the data class and widen `isConfigured`:

```kotlin
data class RouterSettings(
    val host: String = DEFAULT_HOST,
    val username: String = DEFAULT_USERNAME,
    val password: String = "",
    val pollSeconds: Int = DEFAULT_POLL_SECONDS,
    val demoMode: Boolean = false,
) {
    /**
     * Both an address we will talk to and a password to talk with — or demo
     * mode, which needs neither because it never leaves the process.
     */
    val isConfigured: Boolean
        get() = demoMode || (HostValidation.isValid(host) && password.isNotEmpty())
```

Add the import for the boolean key at the top of the file:

```kotlin
import androidx.datastore.preferences.core.booleanPreferencesKey
```

Read it in the `settings` flow:

```kotlin
    val settings: Flow<RouterSettings> = store.data.map { prefs ->
        RouterSettings(
            host = prefs[KEY_HOST] ?: RouterSettings.DEFAULT_HOST,
            username = prefs[KEY_USERNAME] ?: RouterSettings.DEFAULT_USERNAME,
            // Stored sealed; an unrecoverable value reads back empty, which
            // presents as "not configured" and re-prompts.
            password = SecretCipher.open(prefs[KEY_PASSWORD].orEmpty()),
            pollSeconds = prefs[KEY_POLL_SECONDS] ?: RouterSettings.DEFAULT_POLL_SECONDS,
            demoMode = prefs[KEY_DEMO_MODE] ?: false,
        )
    }
```

Add the setter next to `setPollSeconds`:

```kotlin
    suspend fun setDemoMode(enabled: Boolean) {
        store.edit { prefs -> prefs[KEY_DEMO_MODE] = enabled }
    }
```

And the key in the companion:

```kotlin
        val KEY_DEMO_MODE = booleanPreferencesKey("demo_mode")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*RouterSettingsTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/zlt/companion/data/SettingsStore.kt \
        app/src/test/kotlin/dev/zlt/companion/data/RouterSettingsTest.kt
git commit -m "Persist a demo mode flag"
```

---

### Task 5: Route the repository through demo mode

**Files:**
- Modify: `app/src/main/kotlin/dev/zlt/companion/data/RouterRepository.kt` (the `from` factory in the companion object)
- Modify: `app/src/main/kotlin/dev/zlt/companion/ui/RouterViewModel.kt`

**Interfaces:**
- Consumes: `DemoTransport.okHttpClient()`, `DemoTransport.CREDENTIALS` (Task 3); `RouterSettings.demoMode`, `SettingsStore.setDemoMode` (Task 4).
- Produces: `RouterViewModel.setDemoMode(enabled: Boolean)`; a repository that serves demo data whenever the flag is on.

- [ ] **Step 1: Make the factory demo-aware**

In `RouterRepository.kt`, replace the companion object:

```kotlin
    companion object {
        /**
         * In demo mode every request is answered in-process by [DemoTransport],
         * so no traffic leaves the app and no router is needed. The seam is the
         * client's `baseClient`, which means everything above it — session,
         * parsing, this repository — behaves identically either way.
         */
        fun from(settingsStore: SettingsStore): RouterRepository = RouterRepository(
            credentialsProvider = {
                val settings = settingsStore.current()
                if (settings.demoMode) DemoTransport.CREDENTIALS else settings.credentials()
            },
            clientFactory = { credentials ->
                if (credentials == DemoTransport.CREDENTIALS) {
                    ZltClient(credentials, baseClient = DemoTransport.okHttpClient())
                } else {
                    ZltClient(credentials)
                }
            },
        )
    }
```

Add the import at the top of the file:

```kotlin
import dev.zlt.companion.net.DemoTransport
```

- [ ] **Step 2: Guard the tile cache and add the toggle in the ViewModel**

In `RouterViewModel.kt`, the `refresh()` method currently calls `statusCache.store(...)` unconditionally. Demo readings must not reach the tile. Replace that line:

```kotlin
                // Kept for the Quick Settings tile, which cannot poll on its
                // own. Never written in demo mode: the tile sits outside the
                // app and must not report signal from a router that is not there.
                if (settings.value?.demoMode != true) {
                    statusCache.store(snapshot.status.reading)
                }
```

Add the toggle alongside `setPollSeconds`:

```kotlin
    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDemoMode(enabled)
            // The data source changed, so nothing on screen still applies.
            consecutiveFailures = 0
            _state.value = StatusUiState()
            refresh()
        }
    }
```

- [ ] **Step 3: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 71 tests, 0 failures.

- [ ] **Step 4: Verify it compiles for the device**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/zlt/companion/data/RouterRepository.kt \
        app/src/main/kotlin/dev/zlt/companion/ui/RouterViewModel.kt
git commit -m "Serve demo mode through the repository, keeping it out of the tile cache"
```

---

### Task 6: Demo mode in the UI

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/dev/zlt/companion/ui/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/dev/zlt/companion/ui/StatusScreen.kt`
- Modify: `app/src/main/kotlin/dev/zlt/companion/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `RouterViewModel.setDemoMode` (Task 5), `RouterSettings.demoMode` (Task 4).
- Produces: `SettingsScreen` gains `onTryDemo: (() -> Unit)?`; `StatusScreen` gains `demoMode: Boolean` and `onExitDemo: () -> Unit`.

- [ ] **Step 1: Add the strings**

Append to `app/src/main/res/values/strings.xml`, before `</resources>`:

```xml
    <!-- Demo mode: lets the app be evaluated, and reviewed, without a router -->
    <string name="demo_try">Try it without a router</string>
    <string name="demo_explain">See how the app looks using sample readings. Nothing is sent anywhere.</string>
    <string name="demo_banner">Demo mode — these are sample readings, not your router.</string>
    <string name="demo_exit">Leave demo</string>
```

- [ ] **Step 2: Offer demo mode on the setup screen**

In `SettingsScreen.kt`, add the parameter to the signature (after `onBack`):

```kotlin
fun SettingsScreen(
    settings: RouterSettings,
    showPollInterval: Boolean,
    onSave: (host: String, username: String, password: String) -> Unit,
    onPollSecondsChange: (Int) -> Unit,
    onBack: (() -> Unit)?,
    /** Null once the app is set up for real — only offered during first setup. */
    onTryDemo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
```

At the end of the screen's main `Column`, after the save button, add:

```kotlin
        if (onTryDemo != null) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.demo_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onTryDemo) {
                Text(stringResource(R.string.demo_try))
            }
        }
```

Add whichever of these imports the file does not already have:

```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
```

- [ ] **Step 3: Show the demo banner on the status screen**

In `StatusScreen.kt`, add the parameters:

```kotlin
fun StatusScreen(
    state: StatusUiState,
    host: String,
    demoMode: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitDemo: () -> Unit,
    modeControl: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
```

As the first child of the screen's `Column`, before anything else:

```kotlin
            if (demoMode) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.demo_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        TextButton(onClick = onExitDemo) {
                            Text(stringResource(R.string.demo_exit))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
```

- [ ] **Step 4: Wire it up in MainActivity**

In `MainActivity.kt`, pass the new parameters. The `SettingsScreen` call becomes:

```kotlin
        SettingsScreen(
            settings = current,
            showPollInterval = current.isConfigured,
            onSave = { host, username, password ->
                viewModel.saveSettings(host, username, password)
                settingsOpen = false
            },
            onPollSecondsChange = viewModel::setPollSeconds,
            onBack = if (current.isConfigured) ({ settingsOpen = false }) else null,
            // Only during first setup: once there is a real router configured,
            // offering a fake one is just confusing.
            onTryDemo = if (!current.isConfigured) ({ viewModel.setDemoMode(true) }) else null,
        )
```

And the `StatusScreen` call:

```kotlin
    StatusScreen(
        state = state,
        host = current.host,
        demoMode = current.demoMode,
        onRefresh = { viewModel.refresh() },
        onOpenSettings = { settingsOpen = true },
        onExitDemo = { viewModel.setDemoMode(false) },
        modeControl = {
            ModeControl(
                mode = state.mode,
                changingMode = state.changingMode,
                onSelect = viewModel::setMode,
            )
        },
    )
```

- [ ] **Step 5: Build and test**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 71 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/kotlin/dev/zlt/companion/ui/
git commit -m "Offer demo mode at setup and mark it clearly while it is on"
```

---

### Task 7: Release keystore and a verified minified build

**Files:**
- Create: `keystore.properties` (gitignored)
- Create: `~/zlt-companion-release.jks` (outside the repo)
- Modify: `app/proguard-rules.pro` (only if R8 breaks something)

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces: a signed AAB at `app/build/outputs/bundle/release/app-release.aab`.

**This task is where latent R8 problems surface.** `isMinifyEnabled` has been true since the first commit but the app has never shipped through R8, and `kotlinx-serialization-json` is used reflectively as a parser. Treat a crash here as expected work, not a surprise.

- [ ] **Step 1: Generate the keystore**

Pick a strong password and use it for both `-storepass` and `-keypass`:

```bash
keytool -genkeypair -v \
  -keystore ~/zlt-companion-release.jks \
  -alias zlt-companion \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Exbyte, OU=, O=Exbyte, L=, ST=, C=" \
  -storepass '<PASSWORD>' -keypass '<PASSWORD>'
```

**Back this file up somewhere off this machine before going further.** Losing it means never being able to update the app on Play again.

- [ ] **Step 2: Write keystore.properties**

```bash
cp keystore.properties.example keystore.properties
```

Then fill it in — it is gitignored, so it stays local:

```properties
storeFile=/home/exbyte/zlt-companion-release.jks
storePassword=<PASSWORD>
keyAlias=zlt-companion
keyPassword=<PASSWORD>
```

- [ ] **Step 3: Build the signed bundle**

Run: `./gradlew bundleRelease`
Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/bundle/release/app-release.aab` exists.

- [ ] **Step 4: Confirm it is actually signed**

Run:

```bash
~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs \
  --min-sdk-version 26 app/build/outputs/bundle/release/app-release.aab 2>&1 | head
```

Expected: a certificate is printed, not "DOES NOT VERIFY". (If `apksigner` refuses the `.aab`, build `assembleRelease` and verify the APK instead — the signing config is shared.)

- [ ] **Step 5: Install the minified build and exercise it**

Requires a connected device or the emulator from Task 9.

```bash
./gradlew assembleRelease
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

Open the app, enter demo mode, expand Details, change the network mode. Watch for crashes:

```bash
~/Android/Sdk/platform-tools/adb logcat -s AndroidRuntime:E
```

Expected: no `ClassNotFoundException`, no `SerializationException`, no `NoSuchMethodError`. If any appear, add the needed keep rule to `app/proguard-rules.pro` — for the serialization parser that is:

```proguard
# kotlinx-serialization is used reflectively as a parser for the device's flat
# JSON responses, so R8 must not strip its internals.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

Re-run this step until the app runs clean.

- [ ] **Step 6: Commit**

Only `proguard-rules.pro` is committable here — the keystore and its properties are gitignored and must stay that way.

```bash
git add app/proguard-rules.pro
git commit -m "Keep the serialization parser through R8"
```

Skip the commit if `proguard-rules.pro` needed no change. Then confirm nothing secret is staged:

```bash
git status --porcelain   # must not list keystore.properties or any .jks
```

---

### Task 8: Store graphics

**Files:**
- Create: `store/icon-512.png`
- Create: `store/feature-graphic-1024x500.png`
- Create: `store/README.md`

**Interfaces:**
- Consumes: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/values/colors.xml`.
- Produces: the two required Play graphics as PNGs.

- [ ] **Step 1: Read the source colours and icon**

Run:

```bash
cat app/src/main/res/values/colors.xml
cat app/src/main/res/drawable/ic_launcher_foreground.xml
```

Note `ic_launcher_background` — the generated graphics must use that exact value so the store art matches the installed icon.

- [ ] **Step 2: Render the 512×512 icon**

Play requires exactly 512×512, 32-bit PNG, under 1 MB, with no transparency and no rounded corners of your own (Play applies its own masking).

Compose an SVG at 512×512: the `ic_launcher_background` colour as a full-bleed rectangle, with the `ic_launcher_foreground` vector path centred and scaled to the same proportion the adaptive icon uses (the foreground occupies the inner 72dp of a 108dp canvas, so scale it to 512 × 72/108 ≈ 341px and centre it). Then:

```bash
mkdir -p store
rsvg-convert -w 512 -h 512 store/icon.svg -o store/icon-512.png \
  || python3 -c "import cairosvg; cairosvg.svg2png(url='store/icon.svg', write_to='store/icon-512.png', output_width=512, output_height=512)"
```

- [ ] **Step 3: Render the 1024×500 feature graphic**

Same palette. The app name `Router Signal` set left of centre, the icon mark to its right, on the background colour. No claims, no device photos, and keep text well inside the middle 924×400 — Play crops the edges on some surfaces.

```bash
rsvg-convert -w 1024 -h 500 store/feature.svg -o store/feature-graphic-1024x500.png \
  || python3 -c "import cairosvg; cairosvg.svg2png(url='store/feature.svg', write_to='store/feature-graphic-1024x500.png', output_width=1024, output_height=500)"
```

- [ ] **Step 4: Verify the dimensions and format**

Run:

```bash
file store/icon-512.png store/feature-graphic-1024x500.png
```

Expected: `PNG image data, 512 x 512` and `PNG image data, 1024 x 500`.

- [ ] **Step 5: Commit**

```bash
git add store/
git commit -m "Add the Play Store icon and feature graphic"
```

---

### Task 9: Emulator screenshots from demo mode

**Files:**
- Create: `store/screenshots/*.png`

**Interfaces:**
- Consumes: demo mode (Tasks 3–6), the release or debug APK.
- Produces: at least 4 phone screenshots for the listing.

- [ ] **Step 1: Install the emulator and a system image**

```bash
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root="$HOME/Android/Sdk" \
  "emulator" "system-images;android-36;google_apis;x86_64"
```

- [ ] **Step 2: Create and boot an AVD**

```bash
echo no | ~/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd \
  -n zlt-shots -k "system-images;android-36;google_apis;x86_64" -d pixel_6

~/Android/Sdk/emulator/emulator -avd zlt-shots -no-snapshot -no-boot-anim &
~/Android/Sdk/platform-tools/adb wait-for-device
```

`/dev/kvm` is present and world-writable, so this runs hardware-accelerated.

- [ ] **Step 3: Install the app and enter demo mode**

```bash
./gradlew installDebug
~/Android/Sdk/platform-tools/adb shell monkey -p dev.exbyte.zltcompanion.debug 1
```

Tap **Try it without a router** on the setup screen.

- [ ] **Step 4: Capture the screens**

Four shots: the status screen, Details expanded, the mode switcher mid-change, and the setup screen.

```bash
mkdir -p store/screenshots
for name in status details mode setup; do
  echo "Navigate to: $name, then press enter"; read
  ~/Android/Sdk/platform-tools/adb exec-out screencap -p > store/screenshots/$name.png
done
```

- [ ] **Step 5: Verify they meet Play's rules**

Run: `file store/screenshots/*.png`

Each must be PNG, between 320px and 3840px on every side, with the longer side no more than twice the shorter. A Pixel 6 screenshot (1080×2400) satisfies this. Minimum 2 required; 4 is better.

The demo banner is visible in these shots, which is correct — the listing must not imply sample readings are live ones.

- [ ] **Step 6: Commit**

```bash
git add store/screenshots/
git commit -m "Add Play Store screenshots taken from demo mode"
```

---

### Task 10: Privacy policy and listing copy

**Files:**
- Create: `docs/privacy-policy.md`
- Create: `store/listing.md`

**Interfaces:**
- Consumes: the app's actual behaviour as implemented.
- Produces: a privacy policy ready to host on `exbyte.dev`, and the store listing text.

- [ ] **Step 1: Write the privacy policy**

Create `docs/privacy-policy.md`. Every claim below is true of the code as built — do not add claims beyond it:

```markdown
# Privacy Policy — Router Signal

_Last updated: 29 July 2026_

Router Signal is an app for viewing and changing settings on your own 4G router.

## What is collected

Nothing. The app has no analytics, no crash reporting and no telemetry. No
account is required and none can be created. No personal data is collected,
transmitted, sold or shared with anyone, including the developer.

## What is stored on your device

- The address of your router.
- The username for your router, if you changed it from the default.
- The password for your router.
- How often you would like readings refreshed.

The password is encrypted with an AES-GCM key generated in the Android Keystore,
so what is written to storage is meaningless off your device. These settings are
excluded from Android backups for the same reason. All of it is deleted when you
uninstall the app.

## Network access

The app connects to one thing: the router at the address you enter, over your
local network. It cannot be pointed at a public address — addresses outside the
private ranges are rejected — and it contacts no server belonging to the
developer or anyone else.

Demo mode makes no network connections at all; its readings are generated inside
the app.

## Permissions

`INTERNET` is the only permission the app declares, and it is used solely to
reach your router on your local network.

## Children

The app is a network utility with no user-generated content and no social
features. It is not directed at children.

## Contact

ismailaabubakar112@gmail.com

## Affiliation

Router Signal is an independent app. It is not affiliated with, endorsed by, or
supported by ZTE, MTN, or any carrier or manufacturer. It works with hardware it
was reverse-engineered against; it may stop working after a firmware update.
```

- [ ] **Step 2: Publish it**

Host it at a public URL on `exbyte.dev` — `https://exbyte.dev/router-signal/privacy` — and confirm it loads in a browser with no login. Play rejects a policy URL that 404s or sits behind auth.

- [ ] **Step 3: Write the listing copy**

Create `store/listing.md`:

```markdown
# Play Store listing

## App name (30 char max)
Router Signal — ZLT T10D

## Short description (80 char max)
See your 4G router's signal at a glance and switch network mode without the web UI.

## Full description (4000 char max)
Router Signal shows you what your 4G router is doing, without waiting on its slow
built-in web page.

• Signal strength as clear bars and a plain word — Excellent, Good, Fair, Weak —
  with the dBm figure there when you want it
• Whether the internet is actually connected
• Switch the network mode: Automatic, 4G only, 4G and 3G, 3G only, 2G only
• A Quick Settings tile so signal is one swipe away
• Full details when you want them: RSRP, RSSI, band, SNR, RSRQ and cell ID

Built for the ZLT T10D MAX (ZTE NV8645). It talks to your router over your own
Wi-Fi and to nothing else — no accounts, no analytics, no crash reporting, no
telemetry of any kind. Your router's password is encrypted with a key held in the
Android Keystore and never leaves your phone.

No router? Tap "Try it without a router" to see the whole app running on sample
readings.

Router Signal is an independent app. It is not affiliated with, endorsed by, or
supported by ZTE, MTN, or any carrier or manufacturer. It works with hardware it
was reverse-engineered against, and a firmware update may change or break it.

## App access (Play Console → App content → App access)
All functionality is available without an account. The app's main purpose is
controlling a ZLT T10D MAX 4G router over the local network, which requires that
hardware. To review the app without it, tap "Try it without a router" on the first
screen — this runs the entire app against built-in sample readings.

## Data safety (Play Console → App content → Data safety)
- Does your app collect or share any of the required user data types? **No**
- Is all of the user data collected by your app encrypted in transit? N/A — no data collected
- Do you provide a way for users to request that their data is deleted? N/A — no data collected

The router password is stored on-device only and is never collected or
transmitted, so it is not declared as collected data.

## Content rating
Category: Utility. No user-generated content, no ads, no in-app purchases, no
social features, no location, no data collection.

## Category
Tools

## Privacy policy URL
https://exbyte.dev/router-signal/privacy
```

- [ ] **Step 4: Check the length limits**

Run:

```bash
python3 - <<'PY'
import re, pathlib
text = pathlib.Path("store/listing.md").read_text()
def section(title):
    m = re.search(rf"^## {re.escape(title)}.*?\n(.*?)(?=\n## |\Z)", text, re.S | re.M)
    return m.group(1).strip()
for title, limit in [("App name (30 char max)", 30),
                     ("Short description (80 char max)", 80),
                     ("Full description (4000 char max)", 4000)]:
    body = section(title)
    print(f"{len(body):>5} / {limit}  {'OK' if len(body) <= limit else 'TOO LONG'}  {title}")
PY
```

Expected: `OK` on all three.

- [ ] **Step 5: Commit**

```bash
git add docs/privacy-policy.md store/listing.md
git commit -m "Add the privacy policy and the store listing copy"
```

---

### Task 11: Update the README

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: a README that matches the shipped app.

- [ ] **Step 1: Bring the README in line**

Four things in `README.md` are now wrong or missing:

1. The title and opening still say "ZLT Companion" — the app is **Router Signal**.
2. "What it does" does not mention demo mode. Add a bullet: `- A demo mode, so the app can be seen working without a router`.
3. "Testing" says **63 unit tests**. Update it to whatever `./gradlew testDebugUnitTest` now reports.
4. "Building" says platform 35. Update to platform 36, and note the user-local SDK at `~/Android/Sdk` via `local.properties`.

Add the affiliation disclaimer near the top, matching the `zltrouter` README:

```markdown
> **Disclaimer:** This is an independent, reverse-engineered client. It is **not
> affiliated with, endorsed by, or supported by ZTE, MTN, or any carrier**. The
> API it talks to is undocumented and can break on a firmware update with no
> notice.
```

- [ ] **Step 2: Confirm the test count is accurate**

Run: `./gradlew testDebugUnitTest`

Take the count from the report rather than guessing:

```bash
python3 - <<'PY'
import glob, re, pathlib
total = 0
for f in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    m = re.search(r'tests="(\d+)"', pathlib.Path(f).read_text())
    if m: total += int(m.group(1))
print("tests:", total)
PY
```

Put that number in the README.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "Bring the README in line with the released app"
```

---

### Task 12: Upload and submit

**Files:** none — this is all Play Console, and it needs the developer signed in.

**Interfaces:**
- Consumes: the AAB (Task 7), graphics (Task 8), screenshots (Task 9), copy and policy (Task 10).

- [ ] **Step 1: Create the app**

In Play Console: **Create app**. Name `Router Signal — ZLT T10D`, language English, type App, free.

- [ ] **Step 2: Check whether the closed-testing gate applies**

Look at **Dashboard → Production**. If it requires a closed test first, the account is post-13-Nov-2023 and needs **12 testers opted in for 14 continuous days** before production unlocks. Start this immediately — it is a calendar constraint and nothing else in the plan shortens it.

- [ ] **Step 3: Fill in App content**

Privacy policy URL, App access (paste the section from `store/listing.md` — the demo mode instruction is the part that matters), Ads: no, Content rating questionnaire, Target audience, Data safety: no data collected or shared.

- [ ] **Step 4: Fill in the store listing**

Paste the app name, short and full descriptions from `store/listing.md`. Upload `store/icon-512.png`, `store/feature-graphic-1024x500.png`, and everything in `store/screenshots/`. Category: Tools.

- [ ] **Step 5: Upload the bundle**

Upload `app/build/outputs/bundle/release/app-release.aab` to the closed testing track (or Production, if unlocked). Accept Play App Signing when offered — it is the safety net if the local keystore is ever lost.

- [ ] **Step 6: Submit for review**

Then update `todo.md` with the submission date and what is still outstanding.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| Identity — applicationId, namespace, titles | 1 |
| Demo mode — DemoTransport at the `baseClient` seam | 3 |
| Demo mode — persisted flag | 4 |
| Demo mode — repository routing, tile-cache guard | 5 |
| Demo mode — setup entry, banner | 6 |
| Build config — compileSdk/targetSdk 36, AAB, versioning | 2, 7 |
| Verify the minified release build | 7 |
| Store assets — icon, feature graphic | 8 |
| Store assets — screenshots | 9 |
| Privacy policy | 10 |
| Data safety, App access, content rating | 10 (copy), 12 (entry) |
| Environment — user-local SDK | 2, 9 |
| Testing — DemoTransport contract tests | 3 |
| Testing — suite stays green | every task |
| Docs — README accuracy | 11 |

No spec requirement is unassigned.

**Type consistency:** `DemoTransport.CREDENTIALS` and `DemoTransport.okHttpClient()` are defined in Task 3 and used under those exact names in Tasks 3 and 5. `RouterSettings.demoMode` and `SettingsStore.setDemoMode(Boolean)` are defined in Task 4 and used under those names in Tasks 5 and 6. `RouterViewModel.setDemoMode(Boolean)` is defined in Task 5 and used in Task 6. `StatusScreen`'s new `demoMode`/`onExitDemo` and `SettingsScreen`'s new `onTryDemo` are declared and called consistently within Task 6.

**Known soft spot:** Task 7's R8 keep rules are written for the failure most likely given reflective `kotlinx-serialization` use. The step is written as a loop — run, read the actual crash, add the matching rule — rather than as a fixed rule set, because the real stripping cannot be known before R8 runs.
