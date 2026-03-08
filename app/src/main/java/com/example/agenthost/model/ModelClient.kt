package com.example.agenthost.model

interface ModelClient {
    fun complete(request: ModelRequest): ModelResponse
}
