package com.example.agenthost.core

import android.util.Log

object AgentHostLog {
    fun i(tag: String, message: String): Int = runCatching { Log.i(tag, message) }.getOrDefault(0)

    fun w(tag: String, message: String): Int = runCatching { Log.w(tag, message) }.getOrDefault(0)

    fun e(tag: String, message: String): Int = runCatching { Log.e(tag, message) }.getOrDefault(0)
}
