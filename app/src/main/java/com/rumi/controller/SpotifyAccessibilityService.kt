package com.rumi.controller

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.os.Handler
import android.os.Looper

class SpotifyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "SpotifyAccessibilityService connected and ready")
        
        try {
            val intent = Intent(this, RumiForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopService(Intent(this, RumiForegroundService::class.java))
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        stopService(Intent(this, RumiForegroundService::class.java))
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RumiAccessibility"
        private var instance: SpotifyAccessibilityService? = null

        fun requestPlayback(query: String) {
            val service = instance
            if (service == null) {
                Log.e(TAG, "Accessibility service is dead. Toggle it in settings.")
                return
            }

            Log.d(TAG, "Waiting for Spotify search results to load for: $query")
            
            // 🔥 FIX: Increased from 2000ms to 3500ms to ensure Spotify actually loads the UI
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val rootNode = service.rootInActiveWindow
                    if (rootNode == null) return@postDelayed

                    var targetNode = findNodeByText(rootNode, query)
                    if (targetNode == null) targetNode = findFirstClickableTrack(rootNode)

                    if (targetNode != null) {
                        performClickRecursive(targetNode)
                        Log.d(TAG, "Successfully clicked the song element!")
                        
                        // Auto-Return
                        Handler(Looper.getMainLooper()).postDelayed({
                            service.performGlobalAction(GLOBAL_ACTION_BACK)
                            Handler(Looper.getMainLooper()).postDelayed({
                                service.performGlobalAction(GLOBAL_ACTION_BACK)
                            }, 500) 
                        }, 6000)

                    } else {
                        Log.w(TAG, "Could not find any clickable song result on screen.")
                    }
                    rootNode.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing accessibility click", e)
                }
            }, 3500) 
        }

        /** Invoked only after Yumi's explicit spoken yes/cancel confirmation. */
        fun requestDialCurrent() {
            val service = instance ?: run {
                Log.e(TAG, "Accessibility service is not enabled; cannot press the dial button.")
                return
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val root = service.rootInActiveWindow ?: return@postDelayed
                    val callButton = findDialCallButton(root)
                    if (callButton != null) {
                        val clicked = performClickRecursive(callButton)
                        Log.d(TAG, "Dial confirmation click: $clicked")
                    } else Log.w(TAG, "No safe dial Call control found.")
                    root.recycle()
                } catch (error: Exception) {
                    Log.e(TAG, "Could not click the dial control", error)
                }
            }, 450)
        }

        private fun findDialCallButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val label = listOfNotNull(node.text, node.contentDescription).joinToString(" ").trim().lowercase()
            // Do not match call history/contact rows. Match only a deliberate call action.
            if (label == "call" || label.startsWith("call ") || label == "make call") return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findDialCallButton(child)
                if (found != null) return found
                child.recycle()
            }
            return null
        }

      
        private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
            val className = node.className?.toString() ?: ""
            if (className.contains("EditText", ignoreCase = true)) return null

            val textString = node.text?.toString() ?: ""
            if (textString.contains(text, ignoreCase = true)) return node
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findNodeByText(child, text)
                if (found != null) return found
                child.recycle()
            }
            return null
        }

        private fun findFirstClickableTrack(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val className = node.className?.toString() ?: ""
            if (node.isClickable && !className.contains("EditText", ignoreCase = true)) return node
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findFirstClickableTrack(child)
                if (found != null) return found
                child.recycle()
            }
            return null
        }

        private fun performClickRecursive(node: AccessibilityNodeInfo): Boolean {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            var parent = node.parent
            while (parent != null) {
                val parentClass = parent.className?.toString() ?: ""
                if (parent.isClickable && !parentClass.contains("EditText", ignoreCase = true)) {
                    val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    return clicked
                }
                val oldParent = parent
                parent = parent.parent
                oldParent.recycle()
            }
            return false
        }
    }
}
