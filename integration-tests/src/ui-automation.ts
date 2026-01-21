import { delay } from '@httptoolkit/util';
import { adb, adbShell } from './adb.js';

async function checkVpnActive(): Promise<boolean> {
    try {
        const ifconfig = await adbShell('ip addr show tun0 2>/dev/null || true');
        return ifconfig.includes('tun0');
    } catch {
        return false;
    }
}

export async function dumpUiHierarchy(): Promise<string> {
    try {
        await adbShell('uiautomator dump /sdcard/ui_dump.xml 2>/dev/null || true');
        await delay(500);
        const xml = await adbShell('cat /sdcard/ui_dump.xml 2>/dev/null || echo "<hierarchy></hierarchy>"');
        await adbShell('rm -f /sdcard/ui_dump.xml 2>/dev/null || true');
        return xml;
    } catch {
        return '<hierarchy></hierarchy>';
    }
}

function findTextInXml(xml: string, text: string, exactMatch: boolean = false): { x: number; y: number } | null {
    const escapedText = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const pattern = exactMatch
        ? new RegExp(`text="${escapedText}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`)
        : new RegExp(`text="[^"]*${escapedText}[^"]*"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`, 'i');

    const match = xml.match(pattern);
    if (match) {
        const [, x1, y1, x2, y2] = match.map(Number);
        return { x: Math.round((x1 + x2) / 2), y: Math.round((y1 + y2) / 2) };
    }

    const altPattern = exactMatch
        ? new RegExp(`text='${escapedText}'[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`)
        : new RegExp(`text='[^']*${escapedText}[^']*'[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`, 'i');

    const altMatch = xml.match(altPattern);
    if (altMatch) {
        const [, x1, y1, x2, y2] = altMatch.map(Number);
        return { x: Math.round((x1 + x2) / 2), y: Math.round((y1 + y2) / 2) };
    }

    return null;
}

export async function findElementByText(text: string, exactMatch: boolean = false): Promise<{ x: number; y: number } | null> {
    const xml = await dumpUiHierarchy();
    return findTextInXml(xml, text, exactMatch);
}

export async function findElementByResourceId(resourceId: string): Promise<{ x: number; y: number } | null> {
    const xml = await dumpUiHierarchy();

    const pattern = new RegExp(`resource-id="${resourceId}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`);
    const match = xml.match(pattern);

    if (match) {
        const [, x1, y1, x2, y2] = match.map(Number);
        return { x: Math.round((x1 + x2) / 2), y: Math.round((y1 + y2) / 2) };
    }

    return null;
}

export async function isTextVisible(text: string): Promise<boolean> {
    const xml = await dumpUiHierarchy();
    return xml.toLowerCase().includes(text.toLowerCase());
}

export async function tap(x: number, y: number): Promise<void> {
    await adbShell(`input tap ${x} ${y}`);
}

export async function tapText(text: string, exactMatch: boolean = false): Promise<boolean> {
    const element = await findElementByText(text, exactMatch);
    if (element) {
        await tap(element.x, element.y);
        return true;
    }
    return false;
}

export async function tapResourceId(resourceId: string): Promise<boolean> {
    const element = await findElementByResourceId(resourceId);
    if (element) {
        await tap(element.x, element.y);
        return true;
    }
    return false;
}

export async function pressKey(keycode: string | number): Promise<void> {
    await adbShell(`input keyevent ${keycode}`);
}

export async function pressBack(): Promise<void> {
    await pressKey('KEYCODE_BACK');
}

async function setupScreenLockViaUi(pin: string = '1234'): Promise<boolean> {
    try {
        let tapped = await tapText('Set screen lock', true);
        if (!tapped) {
            tapped = await tapText('Set a screen lock', false);
        }

        if (!tapped) {
            return false;
        }

        await delay(1000);

        tapped = await tapText('PIN', true);
        if (!tapped) {
            tapped = await tapText('pin', false);
        }

        if (!tapped) {
            await pressBack();
            return false;
        }

        await delay(1000);

        const xml = await dumpUiHierarchy();
        if (xml.toLowerCase().includes('skip')) {
            await tapText('No', false) || await tapText('No thanks', false);
            await delay(500);
        }

        await adbShell(`input text ${pin}`);
        await delay(500);
        await pressKey('KEYCODE_ENTER');
        await delay(1000);

        await adbShell(`input text ${pin}`);
        await delay(500);
        await pressKey('KEYCODE_ENTER');
        await delay(1000);

        const finalXml = await dumpUiHierarchy();
        if (finalXml.toLowerCase().includes('notification') ||
            finalXml.toLowerCase().includes('lock screen')) {
            await tapText('Done', true) ||
            await tapText('DONE', true) ||
            await tapText('Skip', true) ||
            await pressBack();
            await delay(500);
        }

        return true;
    } catch {
        return false;
    }
}

/**
 * Handle Android's VPN permission dialog. Checks for early exit if VPN is already active
 * to speed up tests when permission was granted in previous runs.
 */
export async function handleVpnPermissionDialog(timeoutMs: number = 15000): Promise<boolean> {
    const startTime = Date.now();

    while (Date.now() - startTime < timeoutMs) {
        if (await checkVpnActive()) {
            return true;
        }

        const xml = await dumpUiHierarchy();

        if (xml.toLowerCase().includes('connected') &&
            xml.toLowerCase().includes('disconnect') &&
            !xml.toLowerCase().includes('connecting')) {
            await delay(500);
            if (await checkVpnActive()) {
                return true;
            }
        }

        const packageMatch = xml.match(/package="([^"]+)"/);
        const currentPackage = packageMatch ? packageMatch[1] : '';

        if (xml.toLowerCase().includes('send you notifications') ||
            (xml.toLowerCase().includes('notification') && xml.toLowerCase().includes('allow'))) {
            const allowButton = findTextInXml(xml, 'Allow', true);
            if (allowButton) {
                await tap(allowButton.x, allowButton.y);
                await delay(1000);
                continue;
            }
        }

        const isVpnDialogPackage = currentPackage.includes('vpndialogs') ||
            currentPackage.includes('systemui') ||
            currentPackage === 'android';

        const vpnDialogPatterns = [
            'Connection request',
            'VPN connection request',
            'Network monitoring',
            'Set up VPN',
            'wants to set up a VPN connection',
            'Trust this application',
            'wants to create a VPN connection'
        ];

        const hasVpnText = vpnDialogPatterns.some(pattern =>
            xml.toLowerCase().includes(pattern.toLowerCase())
        );

        const isVpnDialog = (hasVpnText && isVpnDialogPackage) ||
            xml.toLowerCase().includes('connection request') ||
            xml.toLowerCase().includes('set up vpn');

        if (xml.toLowerCase().includes('manual setup required')) {
            const skipButton = findTextInXml(xml, 'Skip', true);
            if (skipButton) {
                await tap(skipButton.x, skipButton.y);
                await delay(2000);

                if (await checkVpnActive()) {
                    return true;
                }

                const afterSkipXml = await dumpUiHierarchy();
                if (afterSkipXml.toLowerCase().includes('connection request') ||
                    afterSkipXml.toLowerCase().includes('set up vpn')) {
                    continue;
                }

                await delay(1000);
                if (await checkVpnActive()) {
                    return true;
                }
                continue;
            }

            await delay(500);
            continue;
        }

        if (isVpnDialog) {
            const positiveButtons = ['OK', 'ALLOW', 'Allow', 'Connect', 'CONNECT', 'Yes', 'YES', 'I trust this app'];

            for (const buttonText of positiveButtons) {
                const button = findTextInXml(xml, buttonText, true);
                if (button) {
                    await tap(button.x, button.y);
                    await delay(2000);
                    if (await checkVpnActive()) {
                        return true;
                    }
                    continue;
                }
            }

            const buttonIds = [
                'android:id/button1',
                'com.android.vpndialogs:id/button1',
                'android:id/button2'
            ];

            for (const buttonId of buttonIds) {
                const pattern = new RegExp(`resource-id="${buttonId}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`);
                const match = xml.match(pattern);
                if (match) {
                    const [, x1, y1, x2, y2] = match.map(Number);
                    await tap(Math.round((x1 + x2) / 2), Math.round((y1 + y2) / 2));
                    await delay(2000);
                    if (await checkVpnActive()) {
                        return true;
                    }
                    continue;
                }
            }
        }

        if (xml.toLowerCase().includes('set a screen lock') ||
            xml.toLowerCase().includes('set screen lock') ||
            (xml.toLowerCase().includes('screen lock') && xml.toLowerCase().includes('security'))) {
            const lockSetUp = await setupScreenLockViaUi();
            if (lockSetUp) {
                await pressBack();
                await delay(1000);
                continue;
            } else {
                await pressBack();
                await delay(500);
                return false;
            }
        }

        if (xml.toLowerCase().includes('oh no') || xml.toLowerCase().includes("couldn't connect")) {
            return false;
        }

        await delay(500);
    }

    const finalXml = await dumpUiHierarchy();
    const textElements = finalXml.match(/text="[^"]+"/g) || [];
    console.log('VPN dialog timeout. UI texts:', textElements.slice(0, 15).join(', '));
    return false;
}

export async function handleNotificationPermissionDialog(timeoutMs: number = 10000): Promise<boolean> {
    const startTime = Date.now();

    while (Date.now() - startTime < timeoutMs) {
        const xml = await dumpUiHierarchy();

        if (xml.toLowerCase().includes('notification') &&
            (xml.toLowerCase().includes('permission') || xml.toLowerCase().includes('allow'))) {

            if (await tapText('Allow', true)) {
                await delay(500);
                return true;
            }

            if (await tapResourceId('com.android.permissioncontroller:id/permission_allow_button')) {
                await delay(500);
                return true;
            }
        }

        await delay(500);
    }

    return false;
}

export async function takeScreenshot(localPath: string): Promise<void> {
    await adbShell('screencap /sdcard/screenshot.png');
    await adb(['pull', '/sdcard/screenshot.png', localPath]);
    await adbShell('rm /sdcard/screenshot.png');
}
