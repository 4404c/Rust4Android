package dev.rustdroid.ide

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4A (auditoría A3): el SDK de Anthropic 3.13.0 con Jackson forzado a 2.23
 * (BOM) sigue armando pedidos y leyendo respuestas, por el mismo camino que
 * usa AnthropicChat: params → JSON, respuesta JSON → Message → toParam() →
 * JSON (lo que se guarda en Message.raw) → MessageParam otra vez.
 * Sin red: no gasta cuota.
 */
class AnthropicSdkJacksonTest {

    @Test
    fun `request body serializes with tools effort and cache control`() {
        val tool = Tool.builder()
            .name("read_file")
            .description("Lee un archivo")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(JsonValue.from(mapOf("path" to mapOf("type" to "string"))))
                    .putAdditionalProperty("required", JsonValue.from(listOf("path")))
                    .build(),
            )
            .build()
        val params = MessageCreateParams.builder()
            .model("claude-opus-5-5")
            .maxTokens(64000L)
            .system("Sos un asistente")
            .addUserMessage("Hola")
            .addTool(tool)
            .cacheControl(CacheControlEphemeral.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of("high")).build())
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val json = jsonMapper().writeValueAsString(params._body())
        val tree = jsonMapper().readTree(json)
        assertEquals("claude-opus-5-5", tree["model"].asText())
        assertEquals("read_file", tree["tools"][0]["name"].asText())
        assertEquals("high", tree["output_config"]["effort"].asText())
        assertEquals("default", tree["fallbacks"].asText())
        assertEquals("ephemeral", tree["cache_control"]["type"].asText())
    }

    @Test
    fun `a response with thinking text and tool use round trips through toParam`() {
        val response = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[
               {"type":"thinking","thinking":"","signature":"sig123"},
               {"type":"text","text":"Voy a leerlo","citations":null},
               {"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"src/main.rs"}}
             ],
             "stop_reason":"tool_use","stop_sequence":null,"stop_details":null,
             "usage":{"input_tokens":10,"output_tokens":5,"cache_creation_input_tokens":0,
                      "cache_read_input_tokens":0,"server_tool_use":null,"service_tier":"standard",
                      "cache_creation":null,"inference_geo":null}}
        """.trimIndent()
        val message = jsonMapper().readValue(response, Message::class.java)
        assertEquals(3, message.content().size)
        val use = message.content().mapNotNull { it.toolUse().orElse(null) }.single()
        assertEquals("read_file", use.name())
        assertTrue(jsonMapper().writeValueAsString(use._input()).contains("src/main.rs"))

        // lo que AnthropicChat guarda en Message.raw y devuelve en el próximo pedido
        val raw = jsonMapper().writeValueAsString(message.toParam())
        val back = jsonMapper().readValue(raw, MessageParam::class.java)
        val again = jsonMapper().writeValueAsString(back)
        assertTrue(again.contains("sig123"))
        assertTrue(again.contains("toolu_1"))
    }
}
