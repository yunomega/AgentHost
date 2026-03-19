package com.example.agenthost.session

interface OverlaySessionGateway {
    fun getActiveSession(): SessionState?
    fun replyToSession(sessionId: String, userText: String): SessionResult
    fun showSessionOnMain(sessionId: String? = null): SessionResult
    fun migrateTaskToMain(sessionId: String? = null): SessionResult
    fun migrateTaskToExecution(sessionId: String? = null): SessionResult
    fun stopSession(migrate: Boolean): SessionResult
}
