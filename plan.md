There should be no 'Toggle VPN' button: what does it mean most of the time? Very unclear

# States:

## Never connected / Reset

Shows:
* Instructions
    * Make sure you have HTK running ((i) -> Don't have it? Install it from httptoolkit.tech)
    * Select the 'Android device' option and scan the barcode shown.
    * Link to docs
* Scan button -> Scan
* Manually connect button -> Manually connect

## Scan activity

Shows the camera
When a valid barcode is found:
* Return to main activity with the result

## Manually connect activity

Shows: ip, port, connect button

Enter the ip & port
Connect:
* Return to main activity with the result

## Connect to / loading:

Separate state of the main activity.

Shows a spinner whilst we connect

Jumps to error if we fail
Jumps to connected if we succeed

## Connected:

Separate state of the main activity.

Shows the current HTTP Toolkit connection state & details

>
>      Connected
>
> to 10.0.0.1 on port 8000

Shows a button to disconnect
* Disconnects, shows start UI when complete

---

# Logic:

Initial connection data:
List of ips, port, optional certificate hash

For each ip:
    - Send an *http* request to android.httptoolkit.tech/certificate via the proxy to get the cert
    - If it fails or the cert doesn't match the hash, try the next ip
If one worked, that's our ip, port & cert
If not, show an error like:
> Could not connect to HTTP Toolkit. Is it connected to the same network as this device?
> More info:
> Tried port <port> with addresses:
> - 172.20.10.13: ECONNECT
> - 10.0.0.1: Certificate mismatch

---

Last connection data:
Single ip, port & certificate

Connection test: connect to amiusing.httptoolkit.tech via proxy, examine the result.

---


--------------------

        H T
        T P

 Start HTTP Toolkit,
 select 'Android' on
 the Intercept page,
 and then tap below to
 scan the code shown.

   --------------
---[  Scan Code  ]---
   --------------

 [Connect  manually]

  [Read the docs]

--------------------

--------------------

        H T
        T P

    *Connected*

    to 10.0.0.1
    on port 8000

   --------------
---[  Disconnect  ]---
   --------------

  [Read the docs]

--------------------

--------------------

        H T
        T P


    Connecting...


   -----------------
-[  Please wait...  ]-
   -----------------

  [Read the docs]

--------------------

--------------------

        H T
        T P

 Start HTTP Toolkit,
 select 'Android' on
 the Intercept page,
 and then tap below to
 scan the code shown.

   --------------
---[  Scan Code  ]---
   --------------

   [  Reconnect  ]
   [ to 10.0.0.1 ]

 [Connect  manually]

  [Read the docs]

--------------------