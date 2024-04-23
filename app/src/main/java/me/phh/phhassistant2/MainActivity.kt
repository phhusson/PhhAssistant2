package me.phh.phhassistant2

import android.app.Activity
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSession.AssistState
import android.util.Log
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executor
import javax.net.ssl.HttpsURLConnection

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this)
        tv.text = "Hello world"
        setContentView(tv)
    }
}


fun together_xyz_complete(text: String): String {
    val url = "https://api.together.xyz/inference"
    val body = JSONObject()
    body.put("max_tokens", 512)
    body.put("stream_tokens", false)
    body.put("stop", JSONArray(listOf("</s>", "[/INST]", "<|eot_id|>")))
    body.put("temperature", 0.35)
    body.put("model", "meta-llama/Llama-3-8b-chat-hf")
    body.put("prompt", text)

    // Do the request
    val conn = URL(url).openConnection() as HttpsURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Authorization", "Bearer $TOGETHER_XYZ_TOKEN")
    conn.doOutput = true


    conn.outputStream.write(body.toString().toByteArray())
    conn.outputStream.close()


    // Check the answer code
    if (conn.responseCode != 200) {
        Log.e("PHH-Voice", "Failed to get response from together.xyz: ${conn.responseCode}")
        throw Exception("Failed to get response from together.xyz")
    }

    // Read the response
    val response = conn.inputStream.bufferedReader().readText()
    val json = JSONObject(response)
    return json.getJSONObject("output").getJSONArray("choices").getJSONObject(0).getString("text")
}

class Conversation {
    var state = ""
    val prompt = "Here's a dump of an Android Activity. What is this activity about?"
}

class MainInteractionService: VoiceInteractionService() {
    override fun onBind(intent: Intent?): IBinder? {
        Log.d("PHH-Voice", "Bound to voice interaction service")
        return super.onBind(intent)
    }

    override fun onReady() {
        super.onReady()
        Log.d("PHH-Voice", "Voice interaction service is ready")
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        Log.d("PHH-Voice", "Launching voice assist from keyguard")
    }

    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        super.onPrepareToShowSession(args, showFlags)
        Log.d("PHH-Voice", "Preparing to show session")
    }

    override fun showSession(args: Bundle?, flags: Int) {
        super.showSession(args, flags)
        Log.d("PHH-Voice", "Showing session")
    }
}

class MainInteractionSessionService: android.service.voice.VoiceInteractionSessionService() {
    fun browseViewNode(node: AssistStructure.ViewNode, depth: Int): JSONArray {
        val json = JSONArray()
        for(i in 0 until node.childCount) {
            val child = node.getChildAt(i)
            Log.d("PHH-Voice", "\t".repeat(depth) + "Child $i: ${child.isActivated} ${if(child.visibility == 0) "VISIBLE" else "NOT VISIBLE"} ${child.alpha} ${child.className} ${child.textSize} ${child.text}")

            // Don't browse hidden options...
            if (child.visibility != 0)
                continue

            val htmlInfo = child.htmlInfo
            if(htmlInfo != null) {
                Log.d("PHH-Voice", "\t".repeat(depth) + "HTML info: ${htmlInfo}")
            }
            val ret = browseViewNode(child, depth + 1)

            val obj = JSONObject()
            if (child.text != null) {
                obj.put("text", child.text)
                obj.put("textSize", child.textSize)
            }
            obj.put("class", child.className
                ?.replace("android.widget.", "")
                ?.replace("androidx.widget.", ""))
            if (ret.length() > 0)
                obj.put("children", ret)
            json.put(obj)
        }
        return json
    }

    val handler = Handler(HandlerThread("Assistant").also { it.start()}.looper)

    override fun onNewSession(args: Bundle): VoiceInteractionSession {
        return object:VoiceInteractionSession(this) {
            override fun onHandleAssist(state: AssistState) {
                val data = state.assistData
                val structure = state.assistStructure
                val content = state.assistContent

                Log.d("PHH-Voice", "Received handle assist $data $structure $content activityId ${state.activityId}")
                Log.d("PHH-Voice", "Intent: ${content?.intent}")
                if(content != null && content.intent.extras != null) {
                    val e = content.intent.extras!!
                    for(k in e.keySet()) {
                        Log.d("PHH-Voice", "\t$k - ${content.extras[k]}")
                    }
                }

                Log.d("PHH-Voice", "Structured data ${content?.structuredData}")
                Log.d("PHH-Voice", "Clip data: ${content?.clipData?.description}")
                Log.d("PHH-Voice", "Web URI: ${content?.webUri}")
                if(content?.extras != null) {
                    val e = content.extras!!
                    for(k in e.keySet()) {
                        Log.d("PHH-Voice", "\t$k - ${content.extras[k]}")
                    }
                }

                Log.d("PHH-Voice","data bundle")
                for(k in data!!.keySet()) {
                    Log.d("PHH-Voice", "\t$k - ${data[k]}")
                }

                for(i in 0 until structure!!.windowNodeCount) {
                    val node = structure.getWindowNodeAt(i)
                    Log.d("PHH-Voice", "Window node $i: $node ${node.title}")

                    val rootView = node.rootViewNode
                    val json = JSONArray()
                    for (j in 0 until rootView.childCount) {
                        val child = rootView.getChildAt(j)
                        val ret = browseViewNode(child, 1)
                        if (ret.length() > 0)
                            json.put(ret)
                    }
                    Log.d("PHH-Voice", "View json is $json")
                    handler.post {
                        val request =
                            "<|start_header_id|>system<|end_header_id|>You are a helpful assistant running on a smartphone, and you have access to the user's screen. Here's a dump of an Android Activity. What is this activity about? ${json.toString()}<|eot_id|>\n\n<|start_header_id|>assistant<|end_header_id|>"
                        val response = together_xyz_complete(request)
                        Log.d("PHH-Voice", "Response is $response")
                        finish()
                    }
                }

                val cancelSignal = CancellationSignal()
                requestDirectActions(state.activityId, cancelSignal,
                    { command -> handler.post(command) }) {
                    Log.d("PHH-Voice", "Received direct actions $it")
                }
            }

            override fun onHandleScreenshot(screenshot: Bitmap?) {
                Log.d("PHH-Voice", "Received screenshot $screenshot")
            }
        }
    }
}
