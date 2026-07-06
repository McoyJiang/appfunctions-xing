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
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import com.example.appfunctions.agent.domain.appfunction.ToolConverter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Implementation of [ToolConverter] for OpenAI-compatible function calling. */
@Singleton
class OpenAiToolConverter
    @Inject
    constructor() : ToolConverter<JsonObject> {
        override fun convert(tool: AppFunctionMetadata): JsonObject {
            return convertToolToOpenAiSchema(tool)
        }

        override fun getToolName(tool: AppFunctionMetadata): String {
            return getOpenAiFunctionName(tool)
        }

        private fun convertToolToOpenAiSchema(tool: AppFunctionMetadata): JsonObject {
            val combinedName = getOpenAiFunctionName(tool)
            return buildJsonObject {
                put(KEY_TYPE, JsonPrimitive(VALUE_FUNCTION))
                put(
                    KEY_FUNCTION,
                    buildJsonObject {
                        put(KEY_NAME, JsonPrimitive(combinedName))
                        put(KEY_DESCRIPTION, JsonPrimitive(tool.description))
                        put(
                            KEY_PARAMETERS,
                            buildJsonObject {
                                put(KEY_TYPE, JsonPrimitive(VALUE_OBJECT))
                                put(
                                    KEY_PROPERTIES,
                                    buildJsonObject {
                                        tool.parameters.forEach { parameter ->
                                            val typeSchema =
                                                mapDataTypeToOpenAiSchema(
                                                    parameter.dataType,
                                                    tool.components,
                                                    tool.id,
                                                )
                                            put(
                                                parameter.name,
                                                buildJsonObject {
                                                    typeSchema.forEach { (key, value) ->
                                                        put(key, value)
                                                    }
                                                    put(
                                                        KEY_DESCRIPTION,
                                                        JsonPrimitive(parameter.description),
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                                val requiredParams =
                                    tool.parameters.filter { it.isRequired }.map { it.name }
                                if (requiredParams.isNotEmpty()) {
                                    put(
                                        KEY_REQUIRED,
                                        buildJsonArray {
                                            requiredParams.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

        private fun getOpenAiFunctionName(tool: AppFunctionMetadata): String {
            val baseName = tool.id.replace(INVALID_NAME_CHARS, TOOL_ID_SEPARATOR)
            val components = baseName.split(TOOL_ID_SEPARATOR)
            var result = ""

            // Build from right to left, keeping the most specific parts of the package/id
            for (i in components.indices.reversed()) {
                val component = components[i]
                val separator = if (result.isEmpty()) "" else TOOL_ID_SEPARATOR

                val newLength = result.length + separator.length + component.length
                if (newLength <= MAX_NAME_LENGTH) {
                    result = component + separator + result
                } else {
                    break
                }
            }
            if (result.isEmpty()) {
                result = baseName.takeLast(MAX_NAME_LENGTH)
            }
            return result
        }

        private fun mapDataTypeToOpenAiSchema(
            dataType: AppFunctionDataTypeMetadata,
            components: AppFunctionComponentsMetadata,
            functionId: String,
            visitedReferences: Set<String> = emptySet(),
        ): JsonObject {
            return when (dataType) {
                is AppFunctionStringTypeMetadata ->
                    buildJsonObject {
                        put(KEY_TYPE, JsonPrimitive(VALUE_STRING))
                        val enumValues = dataType.enumValues
                        if (!enumValues.isNullOrEmpty()) {
                            put(
                                KEY_ENUM,
                                buildJsonArray {
                                    for (enumValue in enumValues) {
                                        add(JsonPrimitive(enumValue))
                                    }
                                },
                            )
                        }
                    }
                is AppFunctionLongTypeMetadata ->
                    buildJsonObject { put(KEY_TYPE, JsonPrimitive(VALUE_INTEGER)) }
                is AppFunctionIntTypeMetadata ->
                    buildJsonObject {
                        put(KEY_TYPE, JsonPrimitive(VALUE_INTEGER))
                        val enumValues = dataType.enumValues
                        if (!enumValues.isNullOrEmpty()) {
                            put(
                                KEY_ENUM,
                                buildJsonArray {
                                    for (enumValue in enumValues) {
                                        add(JsonPrimitive(enumValue))
                                    }
                                },
                            )
                        }
                    }
                is AppFunctionBooleanTypeMetadata ->
                    buildJsonObject { put(KEY_TYPE, JsonPrimitive(VALUE_BOOLEAN)) }
                is AppFunctionDoubleTypeMetadata ->
                    buildJsonObject { put(KEY_TYPE, JsonPrimitive(VALUE_NUMBER)) }
                is AppFunctionFloatTypeMetadata ->
                    buildJsonObject { put(KEY_TYPE, JsonPrimitive(VALUE_NUMBER)) }
                is AppFunctionArrayTypeMetadata ->
                    buildJsonObject {
                        put(KEY_TYPE, JsonPrimitive(VALUE_ARRAY))
                        put(
                            KEY_ITEMS,
                            mapDataTypeToOpenAiSchema(
                                dataType.itemType,
                                components,
                                functionId,
                                visitedReferences,
                            ),
                        )
                    }
                is AppFunctionObjectTypeMetadata ->
                    buildJsonObject {
                        put(KEY_TYPE, JsonPrimitive(VALUE_OBJECT))
                        put(
                            KEY_PROPERTIES,
                            buildJsonObject {
                                dataType.properties.forEach { (name, type) ->
                                    put(
                                        name,
                                        mapDataTypeToOpenAiSchema(
                                            type,
                                            components,
                                            functionId,
                                            visitedReferences,
                                        ),
                                    )
                                }
                            },
                        )
                        if (dataType.required.isNotEmpty()) {
                            put(
                                KEY_REQUIRED,
                                buildJsonArray {
                                    dataType.required.forEach { name -> add(JsonPrimitive(name)) }
                                },
                            )
                        }
                    }
                is AppFunctionReferenceTypeMetadata -> {
                    val referenceKey = dataType.referenceDataType
                    if (visitedReferences.contains(referenceKey)) {
                        Log.d(
                            "OpenAiToolConverter",
                            "Circular reference detected for $referenceKey in function $functionId. Breaking cycle.",
                        )
                        return buildJsonObject { put(KEY_TYPE, JsonPrimitive(VALUE_OBJECT)) }
                    }
                    val objectType =
                        components.dataTypes[referenceKey]
                            ?: throw IllegalArgumentException(
                                "Reference type $referenceKey not found in components for function $functionId",
                            )
                    mapDataTypeToOpenAiSchema(
                        objectType,
                        components,
                        functionId,
                        visitedReferences + referenceKey,
                    )
                }
                else ->
                    throw IllegalArgumentException(
                        "Unsupported data type: $dataType for function $functionId",
                    )
            }
        }

        companion object {
            private const val TOOL_ID_SEPARATOR = "_"
            private const val KEY_TYPE = "type"
            private const val VALUE_FUNCTION = "function"
            private const val KEY_FUNCTION = "function"
            private const val KEY_NAME = "name"
            private const val KEY_DESCRIPTION = "description"
            private const val KEY_PARAMETERS = "parameters"
            private const val VALUE_OBJECT = "object"
            private const val KEY_PROPERTIES = "properties"
            private const val KEY_REQUIRED = "required"
            private const val VALUE_STRING = "string"
            private const val VALUE_INTEGER = "integer"
            private const val VALUE_BOOLEAN = "boolean"
            private const val VALUE_NUMBER = "number"
            private const val VALUE_ARRAY = "array"
            private const val KEY_ITEMS = "items"
            private const val KEY_ENUM = "enum"
            private val INVALID_NAME_CHARS = Regex("[^a-zA-Z0-9_]")
            private const val MAX_NAME_LENGTH = 64
        }
    }