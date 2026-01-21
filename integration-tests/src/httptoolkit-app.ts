import { delay } from '@httptoolkit/util';
import { adb, adbShell, forceStopApp } from './adb.js';
import {
    handleVpnPermissionDialog,
    handleNotificationPermissionDialog,
    takeScreenshot
} from './ui-automation.js';
import type { ProxyInfo } from './mock-proxy.js';
import * as path from 'path';
import * as fs from 'fs';

const PACKAGE_NAME = 'tech.httptoolkit.android.v1';

export function buildConnectUrl(proxyInfo: ProxyInfo): string {
    const jsonData = JSON.stringify(proxyInfo);
    const base64Data = Buffer.from(jsonData).toString('base64url');
    return `https://android.httptoolkit.tech/connect?data=${base64Data}`;
}

export async function activateVpn(connectUrl: string, options: {
    handleDialogs?: boolean;
    timeout?: number;
} = {}): Promise<boolean> {
    const { handleDialogs = true, timeout = 30000 } = options;

    await adb([
        'shell', 'am', 'start',
        '-a', 'tech.httptoolkit.android.ACTIVATE',
        '-d', connectUrl
    ]);

    await delay(1000);

    if (handleDialogs) {
        const dialogHandled = await handleVpnPermissionDialog(timeout);

        if (!dialogHandled) {
            const vpnActive = await isVpnActive();
            if (!vpnActive) {
                console.warn('VPN permission dialog may not have been handled');
            }
        }

        await handleNotificationPermissionDialog(5000);
    }

    await delay(2000);
    return await isVpnActive();
}

export async function deactivateVpn(): Promise<void> {
    await adb([
        'shell', 'am', 'start',
        '-a', 'tech.httptoolkit.android.DEACTIVATE'
    ]);
    await delay(2000);
}

export async function isVpnActive(): Promise<boolean> {
    try {
        const ifconfig = await adbShell('ip addr show tun0 2>/dev/null || true');
        if (ifconfig.includes('tun0')) {
            return true;
        }

        const connectivity = await adbShell('dumpsys connectivity | grep -i "type: VPN" || true');
        if (connectivity.toLowerCase().includes('vpn') && connectivity.toLowerCase().includes('connected')) {
            return true;
        }

        const addresses = await adbShell('ip addr');
        if (addresses.includes('169.254.61.43')) {
            return true;
        }

        return false;
    } catch {
        return false;
    }
}

export async function waitForVpnInactive(timeoutMs: number = 10000): Promise<boolean> {
    const startTime = Date.now();

    while (Date.now() - startTime < timeoutMs) {
        if (!await isVpnActive()) {
            return true;
        }
        await delay(500);
    }

    return false;
}

export async function stopApp(): Promise<void> {
    await forceStopApp(PACKAGE_NAME);
}

export async function captureDebugScreenshot(name: string): Promise<string> {
    const screenshotsDir = path.resolve(process.cwd(), 'screenshots');
    if (!fs.existsSync(screenshotsDir)) {
        fs.mkdirSync(screenshotsDir, { recursive: true });
    }

    const filename = `${name}-${Date.now()}.png`;
    const filepath = path.join(screenshotsDir, filename);
    await takeScreenshot(filepath);
    return filepath;
}
