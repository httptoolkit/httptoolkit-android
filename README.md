# httptoolkit-android

Automatic interception of Android HTTP with [HTTP Toolkit](https://httptoolkit.tech/android), for inspection, debugging & mocking.

Looking to file bugs, request features or send feedback? File an issue or vote on existing ones at [github.com/httptoolkit/feedback](https://github.com/httptoolkit/feedback).

## What is this?

HTTP Toolkit is primarily a desktop application. This repo contains the Android app, which connects to that desktop application, and forwards HTTP traffic there.

The Android itself is effectively two parts:

* An outer wrapper, which shows the UI, scans QR codes, retrieves proxy config from HTTP Toolkit, ensures the device trusts HTTP Toolkit's CA certificate, and starts and stops a VPN.
* A VPN, which receives every IP packet sent by the device, parses them, rewrites some of them to go to HTTP Toolkit, and then sends the parsed requests on via the real network (and forwards responses back)

## Contributing

If you're looking to contribute to the Android app itself, you're in the right place. If you're looking to explore or change how the ADB-based Android setup works, you want to take a look at [HTTP Toolkit server](https://github.com/httptoolkit/httptoolkit-server) instead.

You can build and test this Android app in Android studio, like any other. It's half in Kotlin (the outer wrapper) and half in Java (most of the VPN code).

To test the app you can either set up the other components of HTTP Toolkit for development on your machine, or use it with any standard install of HTTP Toolkit. A rooted device isn't required for testing, but you will find that it helps, as you can test with a wider variety of real app traffic.
