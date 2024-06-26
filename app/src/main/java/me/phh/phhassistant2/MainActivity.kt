package me.phh.phhassistant2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread


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
    body.put("max_tokens", 128)
    body.put("stream_tokens", false)
    body.put("stop", JSONArray(listOf("</s>", "[/INST]", "<|eot_id|>")))
    body.put("temperature", 0.15)
    body.put("model", "meta-llama/Llama-3-70b-chat-hf")
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
    val prompt = """
You are a helpful assistant running on an Android smartphone, and you have access to the user's screen. Here's a dump of an Android Activity.
Everything you answer is a formatted JSON. Do not output anything other than a valid JSON object.
This is a smartphone, so most of the interaction are using the touchscreen. Use DPAD only if you know where is the focus.
You might need to run multiples actions to execute a task. You will be provided with the updated view tree at every step of the way.
Wait for the view tree to update before executing the next action.
There is no global search, you must always go inside the app first.

Execute the actions the user asks you to. Here are the actions you can perform:
You can click at a position, by answering a JSON {"function":"click", "pos": [x, y]}
You can swipe up or down from center of the screen with {"function":"swipe", "direction": "up"} or {"function":"swipe", "direction": "down"}
You can type text with: {"function":"input_text", "text", "This is something that will be typed in current text input"}
You can say something to the user with: {"function":"say", "text": "Hello world"}
You can do a global action, one of back, home, notifications, recents, dpad_up, dpad_down, dpad_left, dpad_right, dpad_center . Example: {"function":"global_action", "action": "back"}
You can launch an app just with its name with {"function":"launch_app", "app": "app name"}
Notify that you need to execute another action after the view refresh with {"continue":true}

Example:
User: Enable dark mode
System: [...] (view tree)
Thoughts: Okay we're currently in a web browser, first launch the settings app
{"function":"launch_app", "app": "Settings"}
{"continue":true}
System: [...] (view tree)
Thoughts: We are now at the settings app. Let's click on the display settings
{"function":"click", "pos": [100, 200]}
{"continue":true}
System: [...] (view tree)
Thoughts: I can't see the dark mode there, let's scroll down a bit
{"function":"swipe", "direction": "down"}
{"continue":true}
System: [...] (view tree)
Thoughts: I can now see the dark mode setting. Let's click on the dark mode toggle
{"function":"click", "pos": [350, 1420]}
{"function":"say", "text": "Dark mode enabled"}

Start the conversation by providing the user with just one sentence description of what's on-screen<|eot_id|>
    """.trimIndent()
    enum class Tag {
        TREE, // Description of the view tree (to be pruned)
        ACTION, // Action taken by the model

        // Those won't be pruned
        USER,
        OTHER,
    };
    data class Message(val sender: String, val text: String, val tag: Tag)
    val messages = mutableListOf<Message>(Message("system", prompt, Tag.OTHER))

    fun complete() {
        val request =
            messages
                .joinToString("\n") {
                    "<|start_header_id|>${it.sender}<|end_header_id|>${it.text}<|eot_id|>"
                } + "\n\n<|start_header_id|>assistant<|end_header_id|>"

        Log.d("PHH-TX", "Request is $request")
        val response = together_xyz_complete(request)
        val response2 = response
            .replace(Regex("assistant:"), "")
            .split("\n")
            .map { it.trim() }
            // Take all the lines until one starts with System:
            .takeWhile { !it.startsWith("System:") }
            .joinToString("\n")

        messages.add(Message("assistant", response2, Tag.ACTION))
        Log.d("PHH-Voice", "Response is $response2")
    }
}



class MyAccessibilityService : AccessibilityService() {
    val handler = Handler(HandlerThread("Assistant").also { it.start()}.looper)
    var conversation: Conversation? = null

    var latestTree: AccessibilityNodeInfo? = null

    var appNameCache = mutableMapOf<String, String>()
    fun browseViewNode(node: AccessibilityNodeInfo, depth: Int = 0): JSONArray {
        val json = JSONArray()
        val pos = Rect()

        for(i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            Log.d("PHH-Voice", "\t".repeat(depth) + "Child $i: ${if(child.isVisibleToUser) "VISIBLE" else "NOT VISIBLE"} ${child.className} ${child.extraRenderingInfo?.textSizeInPx} ${child.text}")
            Log.d("PHH-Voice", "\t".repeat(depth) + " - ${child.contentDescription} ${child.stateDescription}")

            // Don't browse hidden options...
            if (!child.isVisibleToUser)
                continue

            // This means the node doesn't provide any useful information. Remove the useless fields to gain few tokens
            val isMeaninglessLayout = child.className == "android.view.View" &&
                    child.text == null &&
                    child.contentDescription == null

            // We'll skip this node altogether in that case
            // TODO: we'd need some img2txt to know what's in there
            val isImageOnlyButton = child.className == "android.widget.Button" &&
                    child.text == null &&
                    child.contentDescription == null &&
                    child.childCount == 0

            if (isImageOnlyButton) {
                Log.d("PHH-Voice", "\t".repeat(depth) + "Useless button with hint ${child.hintText}, error ${child.error}, state ${child.stateDescription}, tooltip ${child.tooltipText}, resourcename ${child.viewIdResourceName}, container title ${child.containerTitle}")
                continue
            }

            val ret = browseViewNode(child, depth + 1)

            val obj = JSONObject()
            if (child.text != null) {
                obj.put("text", child.text)
                obj.put("textSize", child.extraRenderingInfo?.textSizeInPx)
            } else if(child.contentDescription != null) {
                obj.put("text", child.contentDescription)
            }
            if(child.isFocused)
                obj.put("focused", child.isFocused)
            if (!isMeaninglessLayout)
                obj.put("class", child.className
                    ?.replace(Regex("androidx?.(widget|view)."), ""))
            if (ret.length() > 0)
                obj.put("children", ret)

            if(!isMeaninglessLayout) {
                child.getBoundsInScreen(pos)
                obj.put(
                    "pos",
                    JSONArray().also {
                        it.put((pos.left + pos.right) / 2);
                        it.put((pos.top + pos.bottom) / 2)
                    })
            }
            json.put(obj)
        }
        return json
    }


    private fun installedApps(): JSONArray {
        val packageManager = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resInfos = packageManager.queryIntentActivities(intent, 0)
        val installedApps = JSONArray()
        for (resInfo in resInfos) {
            installedApps.put(resInfo.activityInfo.applicationInfo.loadLabel(packageManager).toString())
            appNameCache[resInfo.activityInfo.applicationInfo.packageName] = resInfo.activityInfo.applicationInfo.loadLabel(packageManager).toString()
        }
        Log.d("PHH-Voice", "Installed apps are $installedApps")
        return installedApps
    }


    fun jsonView(node: AccessibilityNodeInfo): JSONObject {
        val packagename = node.packageName
        val json = JSONObject()
        json.put("current_app", appNameCache[packagename.toString()] ?: packagename)
        json.put("view_tree", browseViewNode(node))
        json.put("installed_apps", installedApps())
        return json
    }

    private fun tap(point: PointF) {
        val tap = StrokeDescription(
            android.graphics.Path().apply{ moveTo(point.x, point.y) },
            0,
            ViewConfiguration.getTapTimeout().toLong()
        )
        val builder = GestureDescription.Builder()
        builder.addStroke(tap)
        dispatchGesture(builder.build(), null, null)
    }

    override fun onServiceConnected() {
        Log.d("PHH-Voice", "Accessibility service connected")
        thread {
            try {
                val serverSocket = ServerSocket(8801, 1, InetAddress.getByName("localhost"))

                while (true) {
                    val socket = serverSocket.accept()

                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()

                    val firstJsonTree = jsonView(latestTree!!)
                    conversation = Conversation()
                    conversation!!.messages.add(Conversation.Message("system", firstJsonTree.toString(), Conversation.Tag.TREE))
                    conversation!!.complete()

                    // Show the conversation to the user
                    // Skip first message, it's the prompt
                    //   and the second message, it's the view tree json
                    val conv = synchronized(this) { conversation }!!
                    for(i in 2 until conv.messages.size) {
                        val m = conv.messages[i]
                        output.write("${m.sender}: ${m.text}\n")
                    }
                    output.flush()

                    var skipUser = false
                    while (true) {
                        val stillAlive = synchronized(this) { conversation != null }
                        if (!stillAlive)
                            break

                        var userStr = ""
                        if (!skipUser) {
                            output.write("> ")
                            output.flush()
                            userStr = (input.readLine() ?: break).trim()
                            if (userStr == "exit")
                                break
                        }

                        conv.messages
                            .filter {it.tag == Conversation.Tag.TREE }
                            .forEach { conv.messages.remove(it) }

                        val newJson = synchronized(this) {
                            jsonView(latestTree!!)
                        }
                        conv.messages.add(Conversation.Message("system", newJson.toString(), Conversation.Tag.TREE))
                        output.write("refreshed tree\n")
                        output.flush()

                        if (!skipUser) {
                            // Add user request to the conversation
                            conv.messages.add(Conversation.Message("user", userStr, Conversation.Tag.USER))

                        }
                        conv.complete()
                        skipUser = false

                        // And show the answer (the last message) to the user
                        val m = conv.messages.last()
                        output.write("${m.sender}: ${m.text}\n")
                        output.write("Executing\n")
                        output.flush()

                        val lines = m.text
                            .split("\n")
                            .map { it.trim() }

                        for (line in lines) {
                            output.write("---- $line\n")
                            output.flush()
                            if (line.startsWith("Thoughts:"))
                                continue
                            try {
                                val json = JSONObject(line)
                                if (json.has("continue") && json.getBoolean("continue")) {
                                    output.write("Will continue without user intervention\n")
                                    Thread.sleep(10000) // TODO: Wait for the view to update
                                    skipUser = true
                                    continue
                                }

                                when (json.getString("function")) {
                                    "click" -> {
                                        val pos = json.getJSONArray("pos")
                                        tap(
                                            PointF(
                                                pos.getDouble(0).toFloat(),
                                                pos.getDouble(1).toFloat()
                                            )
                                        )
                                    }

                                    "global_action" -> {
                                        when (json.getString("action")) {
                                            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                                            "notifications" -> performGlobalAction(
                                                GLOBAL_ACTION_NOTIFICATIONS
                                            )

                                            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                                            "dpad_up" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "dpad_down" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "dpad_left" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "dpad_right" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                            "dpad_center" -> performGlobalAction(GLOBAL_ACTION_BACK)
                                        }
                                    }

                                    "input_text" -> {
                                        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                        if (node != null) {
                                            val arguments = Bundle()
                                            arguments.putCharSequence(
                                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                                json.getString("text")
                                            )
                                            node.performAction(
                                                AccessibilityNodeInfo.ACTION_SET_TEXT,
                                                arguments
                                            )
                                            // Also send back to hide IME...
                                            performGlobalAction(GLOBAL_ACTION_BACK)
                                        } else {
                                            output.write("No input field found\n")
                                            output.flush()

                                            conv.messages.add(Conversation.Message("system", "No input field found\n", Conversation.Tag.OTHER))
                                            skipUser = true
                                        }
                                    }

                                    "swipe" -> {
                                        val rect = Rect()
                                        latestTree!!.getBoundsInScreen(rect)
                                        val center = PointF(
                                            (rect.left + rect.right) / 2f,
                                            (rect.top + rect.bottom) / 2f
                                        )
                                        val direction = json.getString("direction")
                                        val start = PointF(center.x, center.y)
                                        val end = PointF(center.x, center.y)
                                        when (direction) {
                                            "up" -> start.y =
                                                (rect.bottom * 0.8 + rect.top * 0.1).toFloat()

                                            "down" -> start.y =
                                                (rect.top * 0.8 + rect.bottom * 0.1).toFloat()
                                        }
                                        val swipe = StrokeDescription(
                                            android.graphics.Path().apply {
                                                moveTo(start.x, start.y); lineTo(
                                                end.x,
                                                end.y
                                            )
                                            },
                                            0,
                                            500
                                        )
                                        val builder = GestureDescription.Builder()
                                        builder.addStroke(swipe)
                                        dispatchGesture(builder.build(), null, null)
                                    }
                                    "launch_app" -> {
                                        val pkg = appNameCache.filter { it.value == json.getString("app") }.keys.firstOrNull()
                                        val intent = if(pkg != null) packageManager.getLaunchIntentForPackage(pkg) else null
                                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        if (intent != null) {
                                            startActivity(intent)
                                        } else {
                                            output.write("App not found\n")
                                            output.flush()
                                            conv.messages.add(Conversation.Message("system", "App not found\n", Conversation.Tag.OTHER))
                                            skipUser = true
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("PHH-Voice", "Failed to parse JSON", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PHH-Voice", "Failed to start server", e)
            }
        }
    }

    fun getRootNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var p = node
        while(p.parent != null)
            p = p.parent
        return p
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent) {
        Log.d("PHH-Voice", "Accessibility event $p0")
        val p = p0.source
        if (p != null) {
            synchronized(this) {
                latestTree = getRootNode(p)
            }
            // Just for logs
            val json = browseViewNode(latestTree!!)
            Log.e("PHH-A11Y", "Tree is $json")
        }
    }

    override fun onInterrupt() {
        Log.d("PHH-Voice", "Accessibility service interrupted")
    }


    override fun onDestroy() {
        super.onDestroy()
        synchronized(this) {
            conversation = null
        }
    }
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
    fun browseViewNode(node: AssistStructure.ViewNode, depth: Int, left: Int, top: Int): JSONArray {
        val json = JSONArray()
        val myTop = top + node.top
        val myLeft = left + node.left

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
            val ret = browseViewNode(child, depth + 1, myLeft, myTop)

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
            obj.put("pos", JSONArray().also { it.put(myLeft + child.left + child.width/2); it.put(myTop + child.top + child.height/2) })
            json.put(obj)
        }
        return json
    }


    override fun onCreate() {
        super.onCreate()
    }

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
                        val ret = browseViewNode(child, 1, node.left, node.top)
                        if (ret.length() > 0)
                            json.put(ret)
                    }
                    Log.d("PHH-Voice", "View json is $json")

                }
            }

            override fun onHandleScreenshot(screenshot: Bitmap?) {
                Log.d("PHH-Voice", "Received screenshot $screenshot")
            }
        }
    }
}
