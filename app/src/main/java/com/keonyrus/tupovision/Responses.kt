package com.keonyrus.tupovision

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ApiException(val status: Int, message: String) : IOException(message)

/** One finished exchange kept as text so follow-up questions have context. */
data class Turn(val question: String, val answer: String)

/**
 * The OpenAI Responses API called with a ChatGPT plan token. Plan usage only accepts streamed,
 * unstored requests, takes text and images but no audio, and rejects sampling parameters.
 */
object Responses {
  private const val RESPONSES_URL = "https://api.openai.com/v1/responses"
  private const val MODELS_URL = "https://api.openai.com/v1/models"

  fun buildBody(
      model: String,
      instructions: String,
      history: List<Turn>,
      question: String,
      jpeg: ByteArray?,
      reasoningEffort: String?,
  ): JSONObject {
    val input = JSONArray()
    for (turn in history) {
      input.put(message("user", "input_text", turn.question))
      input.put(message("assistant", "output_text", turn.answer))
    }
    val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", question))
    if (jpeg != null) {
      content.put(
          JSONObject()
              .put("type", "input_image")
              .put(
                  "image_url",
                  "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP),
              ),
      )
    }
    input.put(JSONObject().put("role", "user").put("content", content))

    val body =
        JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("store", false)
            .put("stream", true)
    if (reasoningEffort != null) {
      body.put("reasoning", JSONObject().put("effort", reasoningEffort))
    }
    return body
  }

  private fun message(role: String, type: String, text: String): JSONObject =
      JSONObject()
          .put("role", role)
          .put("content", JSONArray().put(JSONObject().put("type", type).put("text", text)))

  /** Emits the answer text piece by piece and completes when the response is finished. */
  fun stream(token: String, body: JSONObject): Flow<String> = channelFlow {
    val connectionRef = AtomicReference<HttpURLConnection?>(null)
    launch(Dispatchers.IO) {
      try {
        val connection = URL(RESPONSES_URL).openConnection() as HttpURLConnection
        connectionRef.set(connection)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        if (status !in 200..299) {
          val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
          throw ApiException(status, errorMessage(status, text))
        }

        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
          readLoop@ while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (event.optString("type")) {
              "response.output_text.delta" -> send(event.optString("delta"))
              "response.completed" -> break@readLoop
              "response.failed",
              "response.incomplete",
              "error" -> throw ApiException(0, eventErrorMessage(event))
            }
          }
        }
        close()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        close(e)
      }
    }
    awaitClose {
      // Closing a TLS socket touches the network, so it must not run on the main thread.
      val connection = connectionRef.get()
      if (connection != null) Thread { runCatching { connection.disconnect() } }.start()
    }
  }

  /** Model ids this account may use with its plan; empty when the list cannot be read. */
  suspend fun listModels(token: String): List<String> =
      withContext(Dispatchers.IO) {
        val connection = URL(MODELS_URL).openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = 15000
          connection.readTimeout = 20000
          connection.setRequestProperty("Authorization", "Bearer $token")
          connection.setRequestProperty("Accept", "application/json")
          val status = connection.responseCode
          if (status !in 200..299) {
            val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw ApiException(status, errorMessage(status, text))
          }
          val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
          val items = root.optJSONArray("models") ?: root.optJSONArray("data") ?: JSONArray()
          val result = ArrayList<String>()
          for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val visibility = item.optString("visibility")
            if (visibility.isNotEmpty() && visibility != "list") continue
            val id = item.optString("slug").ifEmpty { item.optString("id") }
            if (id.isNotEmpty()) result.add(id)
          }
          result
        } finally {
          connection.disconnect()
        }
      }

  private fun errorMessage(status: Int, body: String): String {
    val parsed =
        runCatching {
              val error = JSONObject(body).optJSONObject("error")
              error?.optString("message").orEmpty()
            }
            .getOrDefault("")
    val detail = parsed.ifEmpty { body.take(200) }
    return "HTTP $status: $detail"
  }

  private fun eventErrorMessage(event: JSONObject): String {
    val error =
        event.optJSONObject("error") ?: event.optJSONObject("response")?.optJSONObject("error")
    val message = error?.optString("message").orEmpty()
    val incomplete =
        event.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason")
    return when {
      message.isNotEmpty() -> message
      !incomplete.isNullOrEmpty() -> "Ответ оборван: $incomplete"
      else -> "Запрос не выполнен (${event.optString("type")})"
    }
  }
}
