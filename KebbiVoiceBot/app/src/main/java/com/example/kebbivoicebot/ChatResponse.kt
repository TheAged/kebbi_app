package com.example.kebbivoicebot

data class ChatResponse(
    val reply: String,
    val final_emotion: String? = null,
    val text_emotion: String? = null,
    val audio_emotion: String? = null
)