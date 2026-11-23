package com.auraai.overlay.ai

enum class AiProvider(val id: String, val displayName: String) {
    AUTO("auto", "Auto (Cascade)"),
    POLLINATIONS("pollinations", "Pollinations — Gratis"),
    GEMINI("gemini", "Gemini 1.5 Flash"),
    GROQ("groq", "Groq — Llama3 (Cepat)"),
    DEEPSEEK("deepseek", "DeepSeek Chat"),
    CLAUDE("claude", "Claude Haiku"),
    MISTRAL("mistral", "Mistral Small"),
    OPENAI("openai", "OpenAI GPT-4o Mini"),
    TOGETHER("together", "Together AI"),
    COHERE("cohere", "Cohere Command-R"),
}
