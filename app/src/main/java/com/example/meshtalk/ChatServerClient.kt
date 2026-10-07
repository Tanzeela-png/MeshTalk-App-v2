package com.example.meshtalk

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class ChatServerClient(private val onMessageReceived: (String, String) -> Unit) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    fun startServer(port: Int) {
        if (isRunning) return
        isRunning = true

        Thread {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val socket = serverSocket?.accept()?: break
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            val incomingText = reader.readLine()?: ""
                            val senderIp = socket.inetAddress.hostAddress.replace("/", "")

                            if (incomingText.isNotEmpty()) {
                                onMessageReceived(incomingText, senderIp)
                            }
                            socket.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // YE FUNCTION PURA REPLACE - AB BOOLEAN RETURN KAREGA
    fun sendMessage(ipAddress: String, message: String, port: Int): Boolean {
        var success = false
        val thread = Thread {
            var socket: Socket? = null
            try {
                socket = Socket(ipAddress, port)
                socket.soTimeout = 3000
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(message)
                writer.flush()
                success = true
            } catch (e: Exception) {
                success = false
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }
        }
        thread.start()
        thread.join(3500)
        return success
    }

    fun closeAll() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
    }
}