# HTTP Toolkit Android Integration Tests

Automated integration tests for the HTTP Toolkit Android app using Node.js, Mockttp, and ADB.

## Prerequisites

- Node.js 18+
- Android SDK with ADB in PATH
- A running Android emulator or connected device
- The HTTP Toolkit Android app installed on the device

## Setup

1. **Install dependencies:**
   ```bash
   npm install
   ```

2. **Build and install the Android app:**
   ```bash
   cd ..  # Go to main Android project directory
   ./gradlew assembleDebug
   adb install -t app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Start an emulator (if not already running):**
   ```bash
   emulator -avd <your-avd-name>
   ```

## Running Tests

```bash
# Run all tests
npm test

# Run tests in watch mode
npm run test:watch

# Run a specific test by name
npm run test:single -- "should activate VPN"
```

## Test Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Test Runner (Mocha + TypeScript)                          │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  Mockttp    │  │   ADB       │  │  UI Automation      │ │
│  │  Proxy      │  │   Control   │  │  (VPN dialogs)      │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
└──────────────────────────┬──────────────────────────────────┘
                           │ adb reverse / adb shell
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Android Emulator/Device                                    │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  HTTP Toolkit Android App                               ││
│  │  - Receives ACTIVATE/DEACTIVATE intents                 ││
│  │  - Establishes VPN                                      ││
│  │  - Routes traffic to mock proxy                         ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

## Key Components

### `src/adb.ts`
ADB command helpers for device interaction:
- `adbShell()` - Run shell commands on device
- `deviceCurl()` - Make HTTP requests from device
- `deviceNetcat()` - Raw TCP/UDP connections
- `reversePort()` - ADB reverse port forwarding

### `src/ui-automation.ts`
UI interaction via ADB:
- `handleVpnPermissionDialog()` - Auto-accept VPN permission dialogs
- `tapText()` - Tap UI elements by text
- `waitForText()` - Wait for text to appear

### `src/mock-proxy.ts`
Mockttp-based proxy server:
- Serves HTTP Toolkit config endpoints
- Intercepts and records HTTP traffic
- Provides assertions for verifying requests

### `src/httptoolkit-app.ts`
HTTP Toolkit Android app control:
- `buildConnectUrl()` - Create activation URLs
- `activateVpn()` - Start VPN via intent
- `deactivateVpn()` - Stop VPN via intent
- `isVpnActive()` - Check VPN state

## Test Categories

### App Activation
- VPN activation via ACTIVATE intent
- VPN deactivation via DEACTIVATE intent
- Re-activation without restart

### HTTP Traffic Interception
- HTTP request interception
- HTTPS request interception
- POST requests with body

### TCP Traffic Handling
- Raw TCP connections via netcat

### VPN State Management
- Correct state reporting
- Cleanup on force-stop
- Permission handling

### Error Handling
- Unreachable proxy
- Invalid configuration

## Writing New Tests

```typescript
import { expect } from 'chai';
import { createTestContext, cleanupTestContext } from './setup.js';
import { fullActivationFlow, deviceCurl } from '../src/index.js';

describe('My Feature', function() {
  this.timeout(60000);

  let ctx: TestContext;

  beforeEach(async () => {
    ctx = await createTestContext();
    await fullActivationFlow(ctx.proxy.getProxyInfo());
  });

  afterEach(async () => {
    await cleanupTestContext(ctx);
  });

  it('should do something', async () => {
    // Mock a response
    await ctx.proxy.mockUrl('GET', 'http://example.com/', {
      status: 200,
      body: 'mocked'
    });

    // Make request from device
    const response = await deviceCurl('http://example.com/');

    // Assert
    expect(response.status).to.equal(200);
  });
});
```

## CI Integration

See `.github/workflows/ci.yml` for GitHub Actions configuration. Key points:
- Enable KVM for hardware-accelerated emulator
- Use `reactivecircus/android-emulator-runner` action
- Set up ADB reverse port forwards before tests

## Debugging

### Screenshots
Failed tests automatically capture screenshots to `./screenshots/`.

### Manual screenshot:
```typescript
import { captureDebugScreenshot } from '../src/httptoolkit-app.js';
await captureDebugScreenshot('my-debug');
```

### ADB debugging
```bash
# Check VPN status
adb shell ip addr show tun0

# Check routes
adb shell ip route

# View app logs
adb logcat -s tech.httptoolkit.android

# Dump UI hierarchy
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```

## Troubleshooting

### "Device not found"
- Ensure emulator is running: `adb devices`
- Wait for boot: `adb wait-for-device`

### "App not installed"
- Build and install: `./gradlew assembleDebug && adb install -t app/build/outputs/apk/debug/app-debug.apk`

### "VPN permission dialog not handled"
- Different Android versions have different dialog layouts
- Check `ui-automation.ts` for button text patterns
- Add new patterns if needed for your device

### "Proxy connection refused"
- Check ADB reverse forward: `adb reverse --list`
- Verify proxy is running: the test output shows the port

### Tests timing out
- Increase timeout: `this.timeout(120000)`
- Check device responsiveness
- Look for ANRs in logcat
