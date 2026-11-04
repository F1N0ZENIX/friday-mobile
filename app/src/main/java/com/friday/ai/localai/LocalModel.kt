package com.friday.ai.localai

/** A reviewed, immutable model artifact. Never use a moving branch for downloads. */
data class LocalModel(
    val id: String,
    val name: String,
    val family: String,
    val quantization: String,
    val sizeLabel: String,
    val expectedBytes: Long,
    val expectedSha256: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val description: String
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$fileName?download=true"
}

/** Small, explicit catalog: models are downloaded only after the user asks. */
object LocalModelCatalog {
    val recommended = listOf(
        LocalModel(
            id = "smollm2-135m-q4-k-m",
            name = "SmolLM2 135M Instruct",
            family = "SmolLM2",
            quantization = "GGUF · Q4_K_M",
            sizeLabel = "~105 MB",
            expectedBytes = 105_454_432L,
            expectedSha256 = "2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d",
            repository = "bartowski/SmolLM2-135M-Instruct-GGUF",
            revision = "09816acd5d99df7be770d85ea30822623dab342c",
            fileName = "SmolLM2-135M-Instruct-Q4_K_M.gguf",
            description = "Mais leve; responde melhor a pedidos curtos e simples em português."
        ),
        LocalModel(
            id = "qwen2.5-0.5b-q4-0",
            name = "Qwen2.5 0.5B Instruct",
            family = "Qwen2.5",
            quantization = "GGUF · Q4_0",
            sizeLabel = "~429 MB",
            expectedBytes = 428_730_208L,
            expectedSha256 = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed",
            repository = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            revision = "9217f5db79a29953eb74d5343926648285ec7e67",
            fileName = "qwen2.5-0.5b-instruct-q4_0.gguf",
            description = "Mais capaz, mas ocupa mais espaço e memória."
        )
    )
}
