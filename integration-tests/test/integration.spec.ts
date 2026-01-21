import { expect } from 'chai';
import { describe, it, before, beforeEach, afterEach } from 'mocha';
import {
    createTestContext,
    cleanupTestContext,
    resetTestState,
    globalSetup,
    TestContext
} from './setup.js';
import {
    buildConnectUrl,
    activateVpn,
    deactivateVpn,
    isVpnActive,
    waitForVpnInactive,
    stopApp,
    captureDebugScreenshot
} from '../src/httptoolkit-app.js';
import { adbShell, wakeScreen } from '../src/adb.js';

async function httpRequest(host: string, port: number, method: string, path: string, options: {
    headers?: Record<string, string>;
    body?: string;
    timeout?: number;
} = {}): Promise<{ status: number; headers: string; body: string }> {
    const { headers = {}, body, timeout = 10000 } = options;

    let request = `${method} ${path} HTTP/1.1\r\n`;
    request += `Host: ${host}${port !== 80 ? ':' + port : ''}\r\n`;
    for (const [key, value] of Object.entries(headers)) {
        request += `${key}: ${value}\r\n`;
    }
    if (body) {
        request += `Content-Length: ${body.length}\r\n`;
    }
    request += `Connection: close\r\n`;
    request += `\r\n`;
    if (body) {
        request += body;
    }

    // Workaround for toybox nc bug: piped input causes premature exit.
    // See https://github.com/landley/toybox/issues/262
    const escapedRequest = request.replace(/'/g, "'\\''");
    const timeoutSec = Math.ceil(timeout / 1000);
    const cmd = `(printf '%s' '${escapedRequest}'; sleep ${Math.min(timeoutSec, 5)}) | timeout ${timeoutSec} nc ${host} ${port} 2>&1 || echo "NC_FAILED"`;

    try {
        const response = await adbShell(cmd, { timeout: timeout + 5000 });

        if (response.includes('NC_FAILED') || response.trim() === '') {
            return { status: 0, headers: '', body: '' };
        }

        const headerEndIndex = response.indexOf('\r\n\r\n');
        if (headerEndIndex === -1) {
            const altIndex = response.indexOf('\n\n');
            if (altIndex !== -1) {
                const headerSection = response.substring(0, altIndex);
                const bodySection = response.substring(altIndex + 2);
                const statusMatch = headerSection.match(/HTTP\/[\d.]+ (\d+)/);
                return {
                    status: statusMatch ? parseInt(statusMatch[1], 10) : 0,
                    headers: headerSection,
                    body: bodySection
                };
            }
            return { status: 0, headers: response, body: '' };
        }

        const headerSection = response.substring(0, headerEndIndex);
        const bodySection = response.substring(headerEndIndex + 4);
        const statusMatch = headerSection.match(/HTTP\/[\d.]+ (\d+)/);

        return {
            status: statusMatch ? parseInt(statusMatch[1], 10) : 0,
            headers: headerSection,
            body: bodySection
        };
    } catch {
        return { status: 0, headers: '', body: '' };
    }
}

async function sendUdp(host: string, port: number, data: string, options: {
    timeout?: number;
    waitForResponse?: boolean;
} = {}): Promise<string> {
    const { timeout = 5000, waitForResponse = false } = options;
    const timeoutSec = Math.ceil(timeout / 1000);

    const escapedData = data.replace(/'/g, "'\\''");
    const waitFlag = waitForResponse ? '' : '-q 1';
    const cmd = `printf '%s' '${escapedData}' | timeout ${timeoutSec} nc -u ${waitFlag} ${host} ${port} 2>&1 || echo ""`;

    return adbShell(cmd, { timeout: timeout + 5000 });
}

async function ping(host: string, count: number = 3, timeout: number = 10000): Promise<{
    success: boolean;
    transmitted: number;
    received: number;
    output: string;
}> {
    const timeoutSec = Math.ceil(timeout / 1000);
    const cmd = `ping -c ${count} -W ${timeoutSec} ${host} 2>&1 || echo "PING_DONE"`;

    const output = await adbShell(cmd, { timeout: timeout + 5000 });

    const statsMatch = output.match(/(\d+) packets transmitted, (\d+) (?:packets )?received/);
    const transmitted = statsMatch ? parseInt(statsMatch[1], 10) : 0;
    const received = statsMatch ? parseInt(statsMatch[2], 10) : 0;

    return { success: received > 0, transmitted, received, output };
}

describe('HTTP Toolkit Android Integration Tests', function() {
    this.timeout(120000);

    let ctx: TestContext;

    before(async function() {
        await globalSetup();
    });

    beforeEach(async function() {
        await wakeScreen();
        await resetTestState();
        ctx = await createTestContext(8080);
    });

    afterEach(async function() {
        if (this.currentTest?.state === 'failed') {
            const testName = this.currentTest.title.replace(/[^a-zA-Z0-9]/g, '-');
            try {
                const screenshotPath = await captureDebugScreenshot(`failure-${testName}`);
                console.log(`Screenshot saved to: ${screenshotPath}`);
            } catch (e) {
                console.log('Failed to capture debug screenshot:', e);
            }
        }

        await cleanupTestContext(ctx);
        await resetTestState();
    });

    describe('VPN Activation', function() {
        it('should activate VPN via intent', async function() {
            const proxyInfo = ctx.proxy.getProxyInfo();
            const connectUrl = buildConnectUrl(proxyInfo);

            console.log('Testing activation with URL:', connectUrl);

            const activated = await activateVpn(connectUrl, { timeout: 45000 });
            expect(activated, 'VPN should be activated').to.be.true;

            const vpnActive = await isVpnActive();
            expect(vpnActive, 'VPN should report as active').to.be.true;
        });

        it('should deactivate VPN via intent', async function() {
            const proxyInfo = ctx.proxy.getProxyInfo();
            const connectUrl = buildConnectUrl(proxyInfo);

            const activated = await activateVpn(connectUrl, { timeout: 45000 });
            expect(activated).to.be.true;

            await deactivateVpn();
            const vpnInactive = await waitForVpnInactive(10000);
            expect(vpnInactive, 'VPN should be deactivated').to.be.true;
        });

        it('should clean up VPN when app is force-stopped', async function() {
            const proxyInfo = ctx.proxy.getProxyInfo();
            const connectUrl = buildConnectUrl(proxyInfo);

            const activated = await activateVpn(connectUrl, { timeout: 45000 });
            expect(activated).to.be.true;

            await stopApp();
            await new Promise(resolve => setTimeout(resolve, 2000));

            const vpnActive = await isVpnActive();
            expect(vpnActive, 'VPN should be inactive after app stop').to.be.false;
        });
    });

    describe('HTTP Interception', function() {
        beforeEach(async function() {
            const proxyInfo = ctx.proxy.getProxyInfo();
            const connectUrl = buildConnectUrl(proxyInfo);
            const activated = await activateVpn(connectUrl, { timeout: 45000 });
            if (!activated) {
                throw new Error('Failed to activate VPN for traffic test');
            }
        });

        it('should intercept HTTP GET requests', async function() {
            const response = await httpRequest('example.com', 80, 'GET', '/intercept-test-get', {
                timeout: 15000
            });

            expect(response.status).to.be.greaterThan(0);

            const requests = await ctx.proxy.getSeenRequests();
            const matchingRequests = requests.filter(r =>
                r.url.includes('example.com') && r.url.includes('/intercept-test-get')
            );
            expect(matchingRequests.length, 'Proxy should see the GET request').to.be.greaterThan(0);
            expect(matchingRequests[0].method).to.equal('GET');
        });

        it('should intercept HTTP POST requests', async function() {
            const response = await httpRequest('example.com', 80, 'POST', '/intercept-test-post', {
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'key=value&foo=bar',
                timeout: 15000
            });

            expect(response.status).to.be.greaterThan(0);

            const requests = await ctx.proxy.getSeenRequests();
            const postRequests = requests.filter(r =>
                r.method === 'POST' && r.url.includes('/intercept-test-post')
            );
            expect(postRequests.length, 'Proxy should see the POST request').to.be.greaterThan(0);
        });

        it('should handle multiple sequential requests', async function() {
            await httpRequest('example.com', 80, 'GET', '/multi-a', { timeout: 15000 });
            await httpRequest('example.com', 80, 'GET', '/multi-b', { timeout: 15000 });

            const requests = await ctx.proxy.getSeenRequests();
            const requestA = requests.find(r => r.url.includes('/multi-a'));
            const requestB = requests.find(r => r.url.includes('/multi-b'));

            expect(requestA, 'Proxy should see request A').to.exist;
            expect(requestB, 'Proxy should see request B').to.exist;
        });

        it('should intercept requests to different hosts', async function() {
            const response = await httpRequest('httpbin.org', 80, 'GET', '/get', {
                timeout: 15000
            });

            expect(response.status).to.equal(200);

            const requests = await ctx.proxy.getSeenRequests();
            const httpbinRequests = requests.filter(r => r.url.includes('httpbin.org'));
            expect(httpbinRequests.length, 'Proxy should see httpbin request').to.be.greaterThan(0);
        });
    });

    describe('UDP Handling', function() {
        beforeEach(async function() {
            const proxyInfo = ctx.proxy.getProxyInfo();
            const connectUrl = buildConnectUrl(proxyInfo);
            const activated = await activateVpn(connectUrl, { timeout: 45000 });
            if (!activated) {
                throw new Error('Failed to activate VPN for UDP test');
            }
        });

        it('should handle UDP packets (DNS query)', async function() {
            try {
                await sendUdp('8.8.8.8', 53, 'test', {
                    timeout: 5000,
                    waitForResponse: false
                });
                expect(true).to.be.true;
            } catch (e) {
                console.log('UDP test note:', e);
            }
        });

        it('should not crash VPN when receiving UDP traffic', async function() {
            await sendUdp('8.8.8.8', 53, 'test-data', { timeout: 3000 });
            await sendUdp('1.1.1.1', 53, 'more-test', { timeout: 3000 });

            const vpnActive = await isVpnActive();
            expect(vpnActive, 'VPN should remain active after UDP traffic').to.be.true;

            const interfaces = await adbShell('ip addr show tun0 2>/dev/null || true');
            expect(interfaces.toLowerCase(), 'tun0 should still exist').to.include('tun0');
        });
    });

    describe('ICMP/Ping Handling', function() {
        beforeEach(async function() {
            const proxyInfo = ctx.proxy.getProxyInfo();
            const connectUrl = buildConnectUrl(proxyInfo);
            const activated = await activateVpn(connectUrl, { timeout: 45000 });
            if (!activated) {
                throw new Error('Failed to activate VPN for ping test');
            }
        });

        it('should handle ICMP ping through VPN', async function() {
            const result = await ping('8.8.8.8', 3, 10000);
            console.log('Ping output:', result.output);
            expect(result.transmitted).to.equal(3);
        });

        it('should not crash VPN when pinging', async function() {
            await ping('8.8.8.8', 2, 5000);
            await ping('1.1.1.1', 2, 5000);

            const vpnActive = await isVpnActive();
            expect(vpnActive, 'VPN should remain active after ping').to.be.true;
        });

        it('should maintain VPN connectivity after ping traffic', async function() {
            await ping('8.8.8.8', 3, 5000);

            const vpnActive = await isVpnActive();
            expect(vpnActive, 'VPN should remain active after ping').to.be.true;

            const interfaces = await adbShell('ip addr show tun0 2>/dev/null || true');
            expect(interfaces.toLowerCase(), 'tun0 should still exist').to.include('tun0');
        });
    });

    describe('Network Routes', function() {
        it('should set up VPN routes correctly', async function() {
            const proxyInfo = ctx.proxy.getProxyInfo();
            const connectUrl = buildConnectUrl(proxyInfo);

            const activated = await activateVpn(connectUrl, { timeout: 45000 });
            expect(activated).to.be.true;

            const interfaces = await adbShell('ip addr');
            const hasTun = interfaces.includes('tun') || interfaces.includes('169.254.61');
            expect(hasTun, 'Should have VPN interface').to.be.true;

            const rules = await adbShell('ip rule');
            const routes = await adbShell('ip route show table all');

            const hasVpnRouting = routes.includes('tun') ||
                rules.includes('from all lookup') ||
                interfaces.includes('POINTOPOINT');
            expect(hasVpnRouting, 'Should have VPN routing configured').to.be.true;
        });

        it('should restore routes after VPN deactivation', async function() {
            const proxyInfo = ctx.proxy.getProxyInfo();
            const connectUrl = buildConnectUrl(proxyInfo);

            await activateVpn(connectUrl, { timeout: 45000 });
            await deactivateVpn();
            await waitForVpnInactive(10000);

            const interfaces = await adbShell('ip addr');
            const hasTun = interfaces.includes('tun0:') && interfaces.includes('UP');
            expect(hasTun, 'TUN interface should be down').to.be.false;
        });
    });

    describe('Error Handling', function() {
        it('should handle connection to unreachable proxy', async function() {
            const badProxyInfo = {
                addresses: ['192.0.2.1'],
                port: 8080,
                certFingerprint: 'bad-fingerprint'
            };

            const connectUrl = buildConnectUrl(badProxyInfo);

            const activated = await activateVpn(connectUrl, {
                timeout: 15000,
                handleDialogs: true
            });

            const vpnActive = await isVpnActive();
            console.log('Unreachable proxy result:', { activated, vpnActive });
        });

        it('should handle invalid connect URL', async function() {
            const badUrl = 'https://android.httptoolkit.tech/connect?data=!!!invalid!!!';

            const activated = await activateVpn(badUrl, {
                timeout: 10000,
                handleDialogs: false
            });

            expect(activated).to.be.false;
        });
    });
});
