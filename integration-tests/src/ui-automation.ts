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

export async function findElementByText(text: string, exactMatch: boolean = false): Promise<{ x: number; y: number } | null> {
    const xml = await dumpUiHierarchy();

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
        console.log('  Attempting to set up screen lock via UI...');

        let tapped = await tapText('Set screen lock', true);
        if (!tapped) {
            tapped = await tapText('Set a screen lock', false);
        }

        if (!tapped) {
            console.log('  Could not find "Set screen lock" button');
            return false;
        }

        await delay(1000);

        tapped = await tapText('PIN', true);
        if (!tapped) {
            tapped = await tapText('pin', false);
        }

        if (!tapped) {
            console.log('  Could not find PIN option');
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

        console.log('  Screen lock setup via UI complete');
        return true;
    } catch (e) {
        console.log('  Error setting up screen lock via UI:', e);
        return false;
    }
}

/**
 * Handle Android's VPN permission dialog. Checks for early exit if VPN is already active
 * to speed up tests when permission was granted in previous runs.
 */
export async function handleVpnPermissionDialog(timeoutMs: number = 15000): Promise<boolean> {
    const startTime = Date.now();
    let lastXml = '';

    while (Date.now() - startTime < timeoutMs) {
        if (await checkVpnActive()) {
            return true;
        }

        const xml = await dumpUiHierarchy();
        lastXml = xml;

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
            console.log('  Found notification permission dialog, tapping Allow...');
            if (await tapText('Allow', true)) {
                console.log('  Tapped Allow on notification dialog');
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
            console.log('  Found "Manual setup required" dialog from app');

            if (await tapText('Skip', true)) {
                console.log('  Tapped Skip on manual setup dialog');
                await delay(2000);

                const afterSkipXml = await dumpUiHierarchy();
                if (afterSkipXml.toLowerCase().includes('connection request') ||
                    afterSkipXml.toLowerCase().includes('set up vpn')) {
                    console.log('  VPN dialog appeared after skip, continuing...');
                    continue;
                }

                if (afterSkipXml.toLowerCase().includes('disconnected')) {
                    console.log('  App returned to disconnected state after skip');
                }
                continue;
            }

            if (await tapText('Cancel', true)) {
                console.log('  Tapped Cancel on manual setup dialog');
                await delay(1000);
                return false;
            }
        }

        if (isVpnDialog) {
            console.log(`  Found VPN dialog (package: ${currentPackage}), looking for button...`);

            const positiveButtons = ['OK', 'ALLOW', 'Allow', 'Connect', 'CONNECT', 'Yes', 'YES', 'I trust this app'];

            for (const buttonText of positiveButtons) {
                const tapped = await tapText(buttonText, true);
                if (tapped) {
                    console.log(`  Tapped "${buttonText}" button`);
                    await delay(1000);
                    return true;
                }
            }

            const buttonIds = [
                'android:id/button1',
                'com.android.vpndialogs:id/button1',
                'android:id/button2'
            ];

            for (const buttonId of buttonIds) {
                const tapped = await tapResourceId(buttonId);
                if (tapped) {
                    console.log(`  Tapped button with ID ${buttonId}`);
                    await delay(1000);
                    return true;
                }
            }

            console.log('  Could not find button to tap. UI contains:');
            const buttonMatches = xml.match(/text="[^"]+"/g) || [];
            console.log('  Text elements:', buttonMatches.slice(0, 10).join(', '));
        }

        if (xml.toLowerCase().includes('set a screen lock') ||
            xml.toLowerCase().includes('set screen lock') ||
            (xml.toLowerCase().includes('screen lock') && xml.toLowerCase().includes('security'))) {
            console.log('  Found "Set screen lock" dialog - need to set up screen lock via UI');

            const lockSetUp = await setupScreenLockViaUi();
            if (lockSetUp) {
                console.log('  Screen lock set up, retrying VPN permission...');
                await pressBack();
                await delay(1000);
                continue;
            } else {
                console.log('  Could not set up screen lock via UI');
                await pressBack();
                await delay(500);
                return false;
            }
        }

        if (xml.toLowerCase().includes('oh no') || xml.toLowerCase().includes("couldn't connect")) {
            console.log('  App showing error state');
            return false;
        }

        await delay(500);
    }

    console.log('  VPN dialog timeout. Last UI state had texts:');
    const textMatches = lastXml.match(/text="[^"]+"/g) || [];
    console.log('  ', textMatches.slice(0, 15).join(', '));

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
