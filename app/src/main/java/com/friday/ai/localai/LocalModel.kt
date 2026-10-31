package com.friday.ai.localai

data class LocalModel(
    val id: String,
    val name: String,
    val family: String,
    val quantization: String,
    val sizeLabel: String,
    val repository: String,
    val fileName: String,
    val description: String
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/main/$fileName?download=true"
}

object LocalModelCatalog {
    val recommended = listOf(
        LocalModel("smollm2-360m-q4", "SmolLM2 360M Instruct", "SmolLM2", "GGUF · Q4_K_M",
            "~270 MB", "bartowski/SmolLM2-360M-Instruct-GGUF",
            "SmolLM2-360M-Instruct-Q4_K_M.gguf",
            "Modelo compacto para testes e tarefas simples."),
        LocalModel("qwen3-0.6b-q4", "Qwen3 0.6B", "Qwen3", "GGUF · Q4_K_M",
            "~484 MB", "Qwen/Qwen3-0.6B-GGUF",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Modelo multilíngue compacto; exige mais memória que o SmolLM2.")
    )
}
