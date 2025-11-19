package tech.httptoolkit.android.portfilter

val DEFAULT_PORTS = setOf(
    80, // HTTP
    443, // HTTPS
    4443, 8000, 8080, 8443, 8888, 9000 // Common local dev/testing ports
)

val PORT_DESCRIPTIONS = mapOf(
    80 to "Standard HTTP port",
    81 to "Alternative HTTP port",
    443 to "Standard HTTPS port",
    8000 to "Popular local development HTTP port",
    8001 to "Popular local development HTTP port",
    8008 to "Alternative HTTP port",
    8080 to "Popular local development HTTP port",
    8090 to "Popular local development HTTP port",
    8433 to "Alternative HTTPS port",
    8888 to "Popular local development HTTP port",
    9000 to "Popular local development HTTP port"
)

const val MIN_PORT = 1
const val MAX_PORT = 65535
