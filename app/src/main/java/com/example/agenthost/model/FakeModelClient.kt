package com.example.agenthost.model

class FakeModelClient(
    private val handler: (ModelRequest) -> ModelResponse
) : ModelClient {
    override fun complete(request: ModelRequest): ModelResponse = handler(request)
}
