package au.com.uulabs.uudownloader

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/** A tiny web server on the phone itself, so these tests depend on no site and no network. */
class TestServer {
    val clip: ByteArray = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + Random(7).nextBytes(256 * 1024)

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    val base: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "test-server") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use(::serve) } }
            }
        }
    }

    fun stop() = socket.close()

    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        while (reader.readLine()?.isNotEmpty() == true) Unit
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ').substringBefore('?')
        val out = client.getOutputStream()

        fun respond(type: String, body: ByteArray, declaredLength: Int = body.size) {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $declaredLength\r\nConnection: close\r\n\r\n".toByteArray())
            if (method != "HEAD") out.write(body)
            out.flush()
        }

        fun page(title: String, vararg videos: String) = respond(
            "text/html; charset=utf-8",
            ("<html><head><title>$title</title></head><body>" +
                videos.joinToString("") { "<video src=\"$it\"></video>" } +
                "</body></html>").toByteArray(),
        )

        when (path) {
            "/clip.mp4", "/clip2.mp4", "/clip3.mp4" -> respond("video/mp4", clip)
            "/mislabelled.mp4" -> page("Clip behind a page 語", "/clip.mp4")
            "/nothing.mp4" -> page("No video here")
            "/short.mp4" -> respond("video/mp4", clip.copyOf(1024), declaredLength = clip.size)
            "/watch" -> page("A real title 語", "/clip.mp4")
            "/two" -> page("Two clips", "/clip.mp4", "/clip2.mp4")
            "/slow.mp4", "/slow-file.mp4" -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${clip.size}\r\nConnection: close\r\n\r\n".toByteArray())
                if (method != "HEAD") {
                    clip.toList().chunked(4 * 1024).forEach {
                        out.write(it.toByteArray())
                        out.flush()
                        Thread.sleep(150)
                    }
                }
            }
            "/slow-page" -> page("Slow", "/slow-file.mp4")
            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
    }
}
