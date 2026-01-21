import { spawn } from 'child_process';
import { delay } from '@httptoolkit/util';

async function getScreenSize(): Promise<{ width: number; height: number }> {
    try {
        const result = await adbShell('wm size');
        const match = result.match(/(\d+)x(\d+)/);
        if (match) {
            return {
                width: parseInt(match[1], 10),
                height: parseInt(match[2], 10)
            };
        }
    } catch {}
    return { width: 1080, height: 1920 };
}

export async function wakeScreen(): Promise<void> {
    await adb(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']);
    await delay(500);

    const { width, height } = await getScreenSize();
    const centerX = Math.round(width / 2);
    const swipeStartY = Math.round(height * 0.9);
    const swipeEndY = Math.round(height * 0.2);

    await adb(['shell', 'input', 'swipe',
        String(centerX), String(swipeStartY),
        String(centerX), String(swipeEndY),
        '300'
    ]);
    await delay(500);

    await adb(['shell', 'input', 'keyevent', 'KEYCODE_MENU']);
    await delay(300);
}

export async function adb(args: string | string[], options: { timeout?: number } = {}): Promise<string> {
    const argsArray = typeof args === 'string' ? args.split(' ') : args;
    const timeout = options.timeout ?? 30000;

    return new Promise((resolve, reject) => {
        const proc = spawn('adb', argsArray, {
            timeout,
            stdio: ['pipe', 'pipe', 'pipe']
        });

        let stdout = '';
        let stderr = '';

        proc.stdout.on('data', (data) => {
            stdout += data.toString();
        });

        proc.stderr.on('data', (data) => {
            stderr += data.toString();
        });

        proc.on('close', (code) => {
            if (code === 0) {
                resolve(stdout.trim());
            } else {
                reject(new Error(`ADB command failed (exit ${code}): ${stderr || stdout}`));
            }
        });

        proc.on('error', reject);
    });
}

export async function adbShell(command: string, options: { timeout?: number } = {}): Promise<string> {
    return adb(['shell', command], options);
}

export async function waitForDevice(timeoutMs: number = 60000): Promise<void> {
    const startTime = Date.now();

    while (Date.now() - startTime < timeoutMs) {
        try {
            const state = await adb(['get-state']);
            if (state === 'device') {
                const bootComplete = await adbShell('getprop sys.boot_completed');
                if (bootComplete.trim() === '1') {
                    return;
                }
            }
        } catch {}
        await delay(1000);
    }

    throw new Error(`Timed out waiting for device after ${timeoutMs}ms`);
}

export async function isAppInstalled(packageName: string = 'tech.httptoolkit.android.v1'): Promise<boolean> {
    try {
        const result = await adbShell(`pm list packages ${packageName}`);
        return result.includes(packageName);
    } catch {
        return false;
    }
}

export async function forceStopApp(packageName: string = 'tech.httptoolkit.android.v1'): Promise<void> {
    await adbShell(`am force-stop ${packageName}`);
}

export async function reversePort(devicePort: number, localPort: number): Promise<void> {
    await adb(['reverse', `tcp:${devicePort}`, `tcp:${localPort}`]);
}

export async function removeReverse(devicePort: number): Promise<void> {
    try {
        await adb(['reverse', '--remove', `tcp:${devicePort}`]);
    } catch {}
}

export async function clearReverseForwards(): Promise<void> {
    try {
        await adb(['reverse', '--remove-all']);
    } catch {}
}

/**
 * Remove stale HTTP Toolkit certificate files from Downloads that can block new activations.
 */
export async function cleanupCertificateFiles(): Promise<void> {
    try {
        await adbShell('rm -f /sdcard/Download/*HTTP*Toolkit*Certificate* /sdcard/Download/.pending*HTTP*Toolkit* 2>/dev/null || true');
        await adbShell('am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/ 2>/dev/null || true');
    } catch {}
}
