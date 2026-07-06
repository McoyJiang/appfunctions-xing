/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.appfunctions.agent.data

import android.util.Log
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.example.appfunctions.agent.domain.LlmInput
import com.example.appfunctions.agent.domain.LlmProvider
import com.example.appfunctions.agent.domain.LlmResponse
import com.example.appfunctions.agent.domain.LlmResponsePart
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [LlmProvider] for OpenAI-compatible APIs (e.g. GLM).
 *
 * OpenAI-compatible APIs are stateless, so this provider maintains the conversation
 * history in memory keyed by an interaction ID generated for each turn.
 */
@Singleton
class OpenAiCompatibleProviderImpl
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val toolConverter: OpenAiToolConverter,
        private val settingsRepository: com.example.appfunctions.agent.data.SettingsRepository,
    ) : LlmProvider {
        // In-memory conversation history keyed by interactionId.
        private val conversationHistory = ConcurrentHashMap<String, MutableList<JsonObject>>()

        override suspend fun generateResponse(
            previousInteractionId: String?,
            input: LlmInput,
            tools: List<AppFunctionMetadata>,
            apiKey: String,
            modelName: String,
        ): LlmResponse {
            val baseUrl = settingsRepository.openAiCompatibleBaseUrl.first()
            val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"

            // Retrieve or create conversation history.
            val interactionId = previousInteractionId ?: UUID.randomUUID().toString()
            val history = conversationHistory.getOrPut(interactionId) { mutableListOf() }

            // Append the new input to history.
            when (input) {
                is LlmInput.UserMessage -> {
                    history.add(
                        buildJsonObject {
                            put(KEY_ROLE, JsonPrimitive(VALUE_USER))
                            put(KEY_CONTENT, JsonPrimitive(input.text))
                        },
                    )
                }
                is LlmInput.ToolResponse -> {
                    input.outputs.forEach { output ->
                        val matchingTool = tools.find { it.id == output.functionId }
                        val mappedName =
                            if (matchingTool != null) {
                                toolConverter.getToolName(matchingTool)
                            } else {
                                output.functionId
                            }
                        history.add(
                            buildJsonObject {
                                put(KEY_ROLE, JsonPrimitive(VALUE_TOOL))
                                put(KEY_CONTENT, JsonPrimitive(output.result))
                                put(KEY_TOOL_CALL_ID, JsonPrimitive(output.callId))
                                put(KEY_NAME, JsonPrimitive(mappedName))
                            },
                        )
                    }
                }
            }

            // Build the request body.
            val convertedTools =
                tools.mapNotNull { tool ->
                    try {
                        toolConverter.convert(tool)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Failed to convert tool ${tool.id}: ${e.message}", e)
                        null
                    }
                }

            val requestBody =
                buildJsonObject {
                    put(KEY_MODEL, JsonPrimitive(modelName))
                    put(
                        KEY_MESSAGES,
                        buildJsonArray {
                            // System instruction
                            add(
                                buildJsonObject {
                                    put(KEY_ROLE, JsonPrimitive(VALUE_SYSTEM))
                                    put(KEY_CONTENT, JsonPrimitive(getSystemInstruction()))
                                },
                            )
                            // Conversation history
                            history.forEach { add(it) }
                        },
                    )
                    if (convertedTools.isNotEmpty()) {
                        put(KEY_TOOLS, JsonArray(convertedTools))
                    }
                    put(KEY_STREAM, JsonPrimitive(false))
                }

            Log.d(TAG, "OpenAI-compatible Request Body: $requestBody")

            val response: HttpResponse =
                try {
                    httpClient.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        header(HEADER_AUTHORIZATION, "$VALUE_BEARER $apiKey")
                        setBody(requestBody)
                    }
                } catch (e: Exception) {
                    return LlmResponse.Error("Network error: ${e.message}")
                }

            val responseBodyText = response.bodyAsText()
            Log.d(TAG, "OpenAI-compatible Response Body: $responseBodyText")

            if (response.status.value !in 200..299) {
                return LlmResponse.Error(
                    "OpenAI-compatible API error: ${response.status} - $responseBodyText",
                )
            }

            val jsonResponse = Json.parseToJsonElement(responseBodyText).jsonObject
            val choices = jsonResponse[KEY_CHOICES]?.jsonArray
            val firstChoice = choices?.firstOrNull()?.jsonObject
            val message = firstChoice?.get(KEY_MESSAGE)?.jsonObject
            if (message == null) {
                return LlmResponse.Error("Missing message in response")
            }

            val parts = mutableListOf<LlmResponsePart>()

            // Text content
            val content = message[KEY_CONTENT]?.jsonPrimitive?.contentOrNull
            if (content != null && content.isNotEmpty()) {
                parts.add(LlmResponsePart.Text(content))
            }

            // Tool calls
            val toolCalls = message[KEY_TOOL_CALLS]?.jsonArray
            if (toolCalls != null) {
                for (toolCall in toolCalls) {
                    val toolCallObj = toolCall.jsonObject
                    val function = toolCallObj[KEY_FUNCTION]?.jsonObject
                    val name =
                        function?.get(KEY_NAME)?.jsonPrimitive?.contentOrNull
                            ?: return LlmResponse.Error("Tool call missing function name")
                    val matchingTool =
                        tools.find { tool -> toolConverter.getToolName(tool) == name }
                            ?: return LlmResponse.Error("Called unknown function: $name")

                    val argsString = function[KEY_ARGUMENTS]?.jsonPrimitive?.contentOrNull
                    val argumentsMap = mutableMapOf<String, Any?>()
                    if (argsString != null && argsString.isNotEmpty()) {
                        try {
                            val argsJson = Json.parseToJsonElement(argsString).jsonObject
                            for ((key, value) in argsJson) {
                                argumentsMap[key] = value.toPrimitive()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse tool call arguments: ${e.message}", e)
                        }
                    }
                    val callId =
                        toolCallObj[KEY_ID]?.jsonPrimitive?.contentOrNull
                            ?: UUID.randomUUID().toString()
                    parts.add(
                        LlmResponsePart.ToolCall(
                            packageName = matchingTool.packageName,
                            functionId = matchingTool.id,
                            arguments = argumentsMap,
                            callId = callId,
                        ),
                    )
                }
            }

            // Append the assistant message to history for future turns.
            history.add(message)

            return LlmResponse.Success(
                interactionId = interactionId,
                parts = parts,
            )
        }

        private fun JsonElement.toPrimitive(): Any? {
            return when (this) {
                is JsonPrimitive -> {
                    if (this.isString) return this.content
                    return this.content.toBooleanStrictOrNull()
                        ?: this.content.toLongOrNull()
                        ?: this.content.toDoubleOrNull()
                }
                is JsonObject -> this.mapValues { it.value.toPrimitive() }
                is JsonArray -> this.map { it.toPrimitive() }
            }
        }

        private fun getSystemInstruction(): String {
            val currentDate = LocalDate.now().toString()
            return "You are an assistant running on Android. Be concise, direct and helpful. Today's date is $currentDate."
        }

        companion object {
            private const val TAG = "OpenAiCompatibleProvider"

            private const val KEY_MODEL = "model"
            private const val KEY_MESSAGES = "messages"
            private const val KEY_TOOLS = "tools"
            private const val KEY_STREAM = "stream"
            private const val KEY_ROLE = "role"
            private const val KEY_CONTENT = "content"
            private const val KEY_TOOL_CALLS = "tool_calls"
            private const val KEY_FUNCTION = "function"
            private const val KEY_NAME = "name"
            private const val KEY_ARGUMENTS = "arguments"
            private const val KEY_TOOL_CALL_ID = "tool_call_id"
            private const val KEY_ID = "id"
            private const val KEY_CHOICES = "choices"
            private const val KEY_MESSAGE = "message"

            private const val VALUE_SYSTEM = "system"
            private const val VALUE_USER = "user"
            private const val VALUE_TOOL = "tool"
            private const val VALUE_BEARER = "Bearer"

            private const val HEADER_AUTHORIZATION = "Authorization"
        }
    }