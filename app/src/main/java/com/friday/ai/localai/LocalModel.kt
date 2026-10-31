package com.friday.ai.localai

/** Metadata for a model that can be installed and selected by the owner. */
data class LocalModel(
    val id: String,
    val name: String,
    val family: String,
    val quantization: String,
    val sizeLabel: String,
    val repository: String,
    val description: String
)

/** Curated starting points; the catalog is deliberately not downloaded or refreshed automatically. */
object LocalModelCatalog {
    val recommended = listOf(
        LocalModel(
            id = "smollm2-360m-q4",
            name = "SmolLM2 360M Instruct",
            family = "SmolLM2",
            quantization = "GGUF · Q4_K_M",
            sizeLabel = "~250 MB (varia por arquivo)",
            repository = "HuggingFaceTB/SmolLM2-360M-Instruct",
            description = "Modelo pequeno para comandos simples e conversas curtas."
        ),
        LocalModel(
            id = "qwen3-0.6b-q4",
            name = "Qwen3 0.6B",
            family = "Qwen3",
            quantization = "GGUF · Q4_K_M",
            sizeLabel = "~460 MB (varia por arquivo)",
            repository = "Qwen/Qwen3-0.6B-GGUF",
            description = "Alternativa compacta; pode exigir mais memória durante a geração."
        ),
        LocalModel(
            id = "qwen2.5-1.5b-q4",
            name = "Qwen2.5 1.5B Instruct",
            family = "Qwen2.5",
            quantization = "GGUF · Q4_K_M",
            sizeLabel = "~1 GB (varia por arquivo)",
            repository = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            description = "Opção maior para aparelhos com mais memória disponível."
        ),
        LocalModel(
            id = "llama-3.2-1b-q4",
            name = "Llama 3.2 1B Instruct",
            family = "Llama",
            quantization = "GGUF · Q4_K_M",
            sizeLabel = "~0.8 GB (varia por arquivo)",
            repository = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            description = "Modelo compacto de uso geral; confira a licença antes de usar."
        ),
        LocalModel(
            id = "gemma-3-1b-q4",
            name = "Gemma 3 1B",
            family = "Gemma",
            quantization = "GGUF · Q4_K_M",
            sizeLabel = "~0.7 GB (varia por arquivo)",
            repository = "unsloth/gemma-3-1b-it-GGUF",
            description = "Alternativa compacta; verifique os termos de uso do modelo."
        )
    )
}
