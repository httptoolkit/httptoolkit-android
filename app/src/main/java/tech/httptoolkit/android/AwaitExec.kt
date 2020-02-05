package tech.httptoolkit.android

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ExecResult (
    val exitValue: Int,
    val stdout: String,
    val stderr: String
)

suspend fun readStream(stream: InputStream): String {
    return withContext(Dispatchers.IO) {
        return@withContext stream.bufferedReader().use { it.readText() }
    }
}

suspend fun awaitExec(cmd: Array<String>): ExecResult {
    return supervisorScope {
        withContext(Dispatchers.IO) {
            val proc = Runtime.getRuntime().exec(cmd)

            val stdout = async { readStream(proc.inputStream) }
            val stderr = async { readStream(proc.errorStream) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // TODO: THis is probably wrong, on rooted phones (not emulators), su shows a prompt
                // shows a confirmation prompt, it doesn't just run the command.
                proc.waitFor(5, TimeUnit.SECONDS)
            } else {
                // Before API 26 there was no easy way to do timeouts. Doable manually, but
                // a bit of a pain, and it shouldn't happen, so we just skip it in that case.
                proc.waitFor()
            }

            ExecResult(
                proc.exitValue(),
                stdout.await(),
                stderr.await()
            )
        }
    }
}