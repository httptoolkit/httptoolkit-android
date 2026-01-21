import { MockProxy, createMockProxy } from '../src/mock-proxy.js';
import {
    waitForDevice,
    isAppInstalled,
    clearReverseForwards,
    wakeScreen,
    cleanupCertificateFiles
} from '../src/adb.js';
import {
    stopApp,
    isVpnActive,
    deactivateVpn,
    waitForVpnInactive
} from '../src/httptoolkit-app.js';

const PACKAGE_NAME = 'tech.httptoolkit.android.v1';

export interface TestContext {
    proxy: MockProxy;
}

export async function globalSetup(): Promise<void> {
    console.log('Running global setup...');

    console.log('Waiting for device...');
    await waitForDevice(60000);
    console.log('Device ready');

    console.log('Waking screen...');
    await wakeScreen();

    const installed = await isAppInstalled(PACKAGE_NAME);
    if (!installed) {
        console.log('App not installed - please install the debug APK first');
        console.log('Run: ./gradlew assembleDebug && adb install -t app/build/outputs/apk/debug/app-debug.apk');
        throw new Error(`App ${PACKAGE_NAME} is not installed`);
    }
    console.log('App is installed');

    await clearReverseForwards();

    console.log('Cleaning up certificate files...');
    await cleanupCertificateFiles();
}

export async function createTestContext(devicePort: number = 8080): Promise<TestContext> {
    const proxy = await createMockProxy(0, devicePort);
    return { proxy };
}

export async function cleanupTestContext(ctx: TestContext): Promise<void> {
    if (ctx.proxy?.isRunning) {
        await ctx.proxy.stop();
    }
}

export async function resetTestState(): Promise<void> {
    await wakeScreen();

    if (await isVpnActive()) {
        await deactivateVpn();
        await waitForVpnInactive(10000);
    }

    await stopApp();
    await clearReverseForwards();
}

export const mochaHooks = {
    async beforeAll() {
        await globalSetup();
    }
};
