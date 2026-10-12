package com.example.angi.runtime.openai

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAiCompatibleInferenceEngineTest {

    @Test
    fun normalizesBaseUrlsWithoutDuplicatingV1() {
        assertEquals(
            "https://example.com/v1",
            OpenAiCompatibleInferenceEngine.normalizeBaseUrl("https://example.com")
        )
        assertEquals(
            "https://example.com/v1",
            OpenAiCompatibleInferenceEngine.normalizeBaseUrl("https://example.com/v1/")
        )
        assertEquals(
            "http://127.0.0.1:1234/openai/v1",
            OpenAiCompatibleInferenceEngine.normalizeBaseUrl("http://127.0.0.1:1234/openai")
        )
    }

    @Test
    fun retainsProviderVersionPathsWithoutAppendingV1() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            OpenAiCompatibleInferenceEngine.normalizeBaseUrl(
                "https://open.bigmodel.cn/api/paas/v4/"
            )
        )
        assertEquals(
            "https://example.com/v4",
            OpenAiCompatibleInferenceEngine.normalizeBaseUrl("https://example.com/v4")
        )
    }

    @Test
    fun parsesOpenAiModelList() {
        val body = """
            {
              "object": "list",
              "data": [
                {"id": "model-a", "object": "model"},
                {"id": "model-b", "object": "model"},
                {"id": "model-a", "object": "model"}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("model-a", "model-b"),
            OpenAiCompatibleInferenceEngine.parseModelIds(body)
        )
    }
}
