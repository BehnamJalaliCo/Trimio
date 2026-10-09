package io.trimio.engine.llm

import io.trimio.engine.models.ChatFormat

/**
 * Prompt layouts for local models whose GGUF template llama.cpp cannot apply natively.
 * The system prompt is folded into the first user turn for formats without a system role.
 */
object ChatTemplates {

    fun format(format: ChatFormat, system: String, messages: List<ChatMessage>): String = buildString {
        when (format) {
            // BOS tokens are added by the tokenizer, so they are not written here.
            ChatFormat.ChatMl, ChatFormat.Lfm -> {
                append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                for (m in messages) append("<|im_start|>").append(role(m.role)).append('\n').append(m.text).append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }
            ChatFormat.Gemma -> {
                messages.forEachIndexed { i, m ->
                    val text = if (i == 0 && m.role == ChatRole.User) "$system\n\n${m.text}" else m.text
                    append("<start_of_turn>").append(if (m.role == ChatRole.User) "user" else "model").append('\n')
                    append(text).append("<end_of_turn>\n")
                }
                append("<start_of_turn>model\n")
            }
        }
    }

    private fun role(role: ChatRole) = if (role == ChatRole.User) "user" else "assistant"
}
