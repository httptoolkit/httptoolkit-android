import * as mockttp from 'mockttp';
import * as crypto from 'crypto';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { reversePort, removeReverse, clearReverseForwards } from './adb.js';

export interface ProxyInfo {
    addresses: string[];
    port: number;
    localTunnelPort?: number;
    certFingerprint: string;
}

export class MockProxy {
    private server: mockttp.Mockttp;
    private _port: number = 0;
    private _certificate: string = '';
    private _certFingerprint: string = '';
    private _started: boolean = false;
    private _devicePort: number = 0;

    constructor() {
        this.server = mockttp.getLocal();
    }

    async start(port: number = 0): Promise<void> {
        await this.server.start(port);
        this._port = this.server.port;
        this._started = true;

        const { certPem, fingerprint } = this.generateTestCertificate();
        this._certificate = certPem;
        this._certFingerprint = fingerprint;

        await this.setupDefaultRules();
    }

    private generateTestCertificate(): { certPem: string; fingerprint: string } {
        const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
            modulusLength: 2048,
        });

        const publicKeyDer = publicKey.export({ type: 'spki', format: 'der' });
        const fingerprint = crypto.createHash('sha256')
            .update(publicKeyDer)
            .digest('base64');

        const certPem = this.generateSelfSignedCertWithOpenSSL(privateKey);
        return { certPem, fingerprint };
    }

    private generateSelfSignedCertWithOpenSSL(privateKey: crypto.KeyObject): string {
        const tmpDir = os.tmpdir();
        const keyFile = path.join(tmpDir, `test-key-${process.pid}.pem`);
        const certFile = path.join(tmpDir, `test-cert-${process.pid}.pem`);

        try {
            const keyPem = privateKey.export({ type: 'pkcs8', format: 'pem' }) as string;
            fs.writeFileSync(keyFile, keyPem);

            execSync(
                `openssl req -new -x509 -key "${keyFile}" -out "${certFile}" -days 365 -subj "/CN=HTTP Toolkit Test CA" 2>/dev/null`,
                { stdio: 'pipe' }
            );

            return fs.readFileSync(certFile, 'utf8');
        } finally {
            try { fs.unlinkSync(keyFile); } catch {}
            try { fs.unlinkSync(certFile); } catch {}
        }
    }

    async setupDeviceConnection(devicePort: number = 8080): Promise<void> {
        if (!this._started) {
            throw new Error('Proxy must be started before setting up device connection');
        }

        this._devicePort = devicePort;
        await clearReverseForwards();
        await reversePort(devicePort, this._port);
    }

    async cleanupDeviceConnection(): Promise<void> {
        if (this._devicePort) {
            await removeReverse(this._devicePort);
            this._devicePort = 0;
        }
    }

    private async setupDefaultRules(): Promise<void> {
        await this.server
            .forGet('http://android.httptoolkit.tech/config')
            .thenJson(200, { certificate: this._certificate });

        await this.server
            .forGet('http://amiusing.httptoolkit.tech/certificate')
            .thenReply(200, this._certificate, { 'content-type': 'text/plain' });

        await this.server.forAnyRequest().thenPassThrough();
    }

    async mockUrl(method: string, urlPattern: string, response: {
        status?: number;
        body?: string;
        headers?: Record<string, string>;
    }): Promise<void> {
        let host: string;
        let urlPath: string;
        try {
            const parsed = new URL(urlPattern);
            host = parsed.hostname;
            urlPath = parsed.pathname;
        } catch {
            host = '';
            urlPath = urlPattern;
        }

        const matchFn = (req: mockttp.CompletedRequest) => {
            if (req.method.toUpperCase() !== method.toUpperCase()) {
                return false;
            }

            try {
                const reqUrl = new URL(req.url);
                if (host && reqUrl.hostname !== host) return false;
                if (urlPath && reqUrl.pathname !== urlPath) return false;
                return true;
            } catch {
                return req.url.includes(urlPath);
            }
        };

        await this.server
            .forAnyRequest()
            .matching(matchFn)
            .thenReply(response.status ?? 200, response.body ?? '', response.headers ?? {});
    }

    async getSeenRequests(): Promise<mockttp.CompletedRequest[]> {
        const endpoints = await this.server.getMockedEndpoints();
        const allRequests: mockttp.CompletedRequest[] = [];
        for (const endpoint of endpoints) {
            const requests = await endpoint.getSeenRequests();
            allRequests.push(...requests);
        }
        return allRequests;
    }

    async reset(): Promise<void> {
        await this.server.reset();
        await this.setupDefaultRules();
    }

    async stop(): Promise<void> {
        await this.cleanupDeviceConnection();
        await this.server.stop();
        this._started = false;
    }

    get port(): number {
        return this._port;
    }

    get devicePort(): number {
        return this._devicePort || this._port;
    }

    get certificate(): string {
        return this._certificate;
    }

    get certFingerprint(): string {
        return this._certFingerprint;
    }

    get isRunning(): boolean {
        return this._started;
    }

    getProxyInfo(): ProxyInfo {
        return {
            addresses: ['127.0.0.1'],
            port: this._devicePort || this._port,
            certFingerprint: this._certFingerprint
        };
    }
}

export async function createMockProxy(port: number = 0, devicePort: number = 8080): Promise<MockProxy> {
    const proxy = new MockProxy();
    await proxy.start(port);
    await proxy.setupDeviceConnection(devicePort);
    return proxy;
}
