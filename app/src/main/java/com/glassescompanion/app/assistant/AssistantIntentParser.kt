package com.glassescompanion.app.assistant

import java.util.Calendar

sealed interface AssistantIntent {
    data object TakePhoto : AssistantIntent
    data object StartVideo : AssistantIntent
    data object StopVideo : AssistantIntent
    data object StartAudioRecording : AssistantIntent
    data object StopAudioRecording : AssistantIntent
    /** "stop recording" / "रिकॉर्डिंग रोको": resolved against what is actually recording. */
    data object StopRecordingGeneric : AssistantIntent
    data object MusicPlay : AssistantIntent
    data object MusicPause : AssistantIntent
    data object MusicNext : AssistantIntent
    data object MusicPrevious : AssistantIntent
    data object VolumeUp : AssistantIntent
    data object VolumeDown : AssistantIntent
    data object AnswerCall : AssistantIntent
    data object HangUpCall : AssistantIntent
    data class Reminder(val title: String, val atMs: Long, val timeUnderstood: Boolean) : AssistantIntent
    data class VisualQuestion(val question: String) : AssistantIntent
    data class Chat(val text: String) : AssistantIntent
}

enum class ReplyStyle { ENGLISH, HINDI, HINGLISH }

/**
 * Rule-based intent parser for English, Hindi (Devanagari) and romanized
 * Hinglish. Works offline; anything that is not a device intent becomes Chat.
 */
object AssistantIntentParser {

    private val STOP = listOf("stop", "band", "बंद", "रोको", "रोक", "roko", "rok do", "ruko", "रुको", "khatam", "ख़त्म", "खत्म", "end", "finish")
    private val START = listOf("start", "shuru", "शुरू", "chalu", "चालू", "begin", "karo", "करो", "lo ", "लो")
    private val VIDEO = listOf("video", "वीडियो", "विडियो")
    private val AUDIO = listOf("audio", "ऑडियो", "voice recording", "awaaz record", "आवाज़ रिकॉर्ड", "आवाज रिकॉर्ड", "sound recording")
    private val RECORD = listOf("record", "recording", "रिकॉर्डिंग", "रिकॉर्ड", "रेकॉर्ड")
    private val PHOTO = listOf("photo", "picture", "pic ", "snap", "फोटो", "फ़ोटो", "तस्वीर", "tasveer", "selfie")
    private val PHOTO_VERB = listOf("take", "click", "capture", "khicho", "kheencho", "khinch", "खींचो", "खींच", "lo", "लो", "le lo", "shoot")
    private val VISUAL = listOf(
        "what is this", "what's this", "what am i looking at", "what do you see", "describe", "read this", "what is in front",
        "ye kya hai", "yeh kya hai", "ya kya hai", "ये क्या है", "यह क्या है", "क्या दिख", "kya dikh", "dekh kar", "देखकर", "देख कर",
        "padh kar", "पढ़ो", "पढ़कर", "kya likha", "क्या लिखा", "samne kya", "सामने क्या",
    )
    private val MUSIC = listOf("music", "song", "songs", "gaana", "gana", "गाना", "गाने", "संगीत", "track")
    private val PLAY = listOf("play", "chalao", "चलाओ", "bajao", "बजाओ", "resume", "shuru", "चालू")
    private val PAUSE = listOf("pause", "stop", "rok", "रोको", "band", "बंद", "ruko")
    private val NEXT = listOf("next", "agla", "अगला", "skip", "aage")
    private val PREV = listOf("previous", "pichla", "पिछला", "last song", "peeche", "back")

    private val VOLUME = listOf("volume", "वॉल्यूम", "वॉल्युम", "awaaz", "आवाज़", "आवाज", "sound", "louder", "quieter")
    private val UP = listOf("up", "increase", "badhao", "बढ़ाओ", "बढ़ा", "tez", "तेज़", "तेज", "louder", "zyada", "ज़्यादा", "jyada", "raise")
    private val DOWN = listOf("down", "decrease", "kam", "कम", "ghatao", "घटाओ", "lower", "quieter", "dheere", "धीरे", "reduce")
    private val CALL = listOf("call", "कॉल", "phone", "फ़ोन", "फोन")
    private val ANSWER = listOf("answer", "pick", "uthao", "उठाओ", "receive", "accept", "utha")
    private val HANGUP = listOf("hang up", "hangup", "cut", "kaato", "काटो", "काट", "end call", "reject", "disconnect", "band", "बंद", "rakh do")
    private val REMIND = listOf("remind", "reminder", "याद दिला", "yaad dila", "yaad karana", "रिमाइंडर", "alarm")

    private val HINGLISH_MARKERS = listOf("kya", "hai", "karo", "kar do", "mujhe", "batao", "kaise", "kyun", "mera", "meri", "aap", "kitna", "chahiye", "nahi", "haan", "acha", "accha")

    fun replyStyle(text: String): ReplyStyle {
        if (text.any { it in 'ऀ'..'ॿ' }) return ReplyStyle.HINDI
        val words = text.lowercase().split(Regex("\\W+")).toSet()
        return if (HINGLISH_MARKERS.count { it in words } >= 1) ReplyStyle.HINGLISH else ReplyStyle.ENGLISH
    }

    fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): AssistantIntent {
        val t = " " + normalizeDigits(raw).lowercase().trim() + " "
        fun has(list: List<String>) = list.any { k -> containsTerm(t, k) }

        val isRecord = has(RECORD)
        val isVideo = has(VIDEO)
        val isAudio = has(AUDIO)
        val isStop = has(STOP)

        if (has(REMIND)) {
            val (at, ok) = parseTime(t, nowMs)
            return AssistantIntent.Reminder(raw.trim(), at, ok)
        }
        if (isVideo && (isRecord || has(START) || isStop)) return if (isStop) AssistantIntent.StopVideo else AssistantIntent.StartVideo
        if (isAudio && (isRecord || has(START) || isStop)) return if (isStop) AssistantIntent.StopAudioRecording else AssistantIntent.StartAudioRecording
        if (isRecord) return if (isStop) AssistantIntent.StopRecordingGeneric else AssistantIntent.StartAudioRecording
        if (has(VISUAL)) return AssistantIntent.VisualQuestion(raw.trim())
        if (has(PHOTO) && (has(PHOTO_VERB) || t.trim().split(' ').size <= 3)) return AssistantIntent.TakePhoto
        if (has(CALL)) {
            if (has(ANSWER)) return AssistantIntent.AnswerCall
            if (has(HANGUP)) return AssistantIntent.HangUpCall
        }
        if (has(VOLUME) && !isRecord) {
            if (has(UP)) return AssistantIntent.VolumeUp
            if (has(DOWN)) return AssistantIntent.VolumeDown
        }
        if (has(MUSIC) || has(listOf("skip", "agla", "अगला", "pichla", "पिछला"))) {
            if (has(NEXT)) return AssistantIntent.MusicNext
            if (has(PREV)) return AssistantIntent.MusicPrevious
            if (has(MUSIC)) {
                if (has(PAUSE)) return AssistantIntent.MusicPause
                if (has(PLAY)) return AssistantIntent.MusicPlay
            }
        }
        if (t.trim() in listOf("pause", "play", "resume")) return if (t.trim() == "pause") AssistantIntent.MusicPause else AssistantIntent.MusicPlay
        return AssistantIntent.Chat(raw.trim())
    }

    /** ASCII terms match on word boundaries; Devanagari terms match as substrings. */
    fun containsTerm(text: String, term: String): Boolean {
        val k = term.trim()
        if (k.isEmpty()) return false
        return if (k.all { it.code < 128 }) {
            Regex("(?<![a-z])" + Regex.escape(k) + "(?![a-z])").containsMatchIn(text)
        } else {
            text.contains(k)
        }
    }

    fun normalizeDigits(s: String): String = buildString {
        for (c in s) append(if (c in '०'..'९') ('0' + (c - '०')) else c)
    }

    /** Returns (epoch ms, whether a time was actually understood). */
    fun parseTime(t: String, nowMs: Long): Pair<Long, Boolean> {
        val rel = Regex("(\\d+)\\s*(minute|minutes|min|mins|मिनट|minat)").find(t)
        if (rel != null) return nowMs + rel.groupValues[1].toLong() * 60_000 to true
        val relH = Regex("(\\d+)\\s*(hour|hours|hr|hrs|ghante|ghanta|घंटे|घंटा)").find(t)
        if (relH != null) return nowMs + relH.groupValues[1].toLong() * 3_600_000 to true
        val abs = Regex("(?:at\\s*)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|baje|बजे|o'clock)").find(t)
        if (abs != null) {
            var hour = abs.groupValues[1].toInt()
            val minute = abs.groupValues[2].toIntOrNull() ?: 0
            val suffix = abs.groupValues[3]
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            if (suffix == "pm" && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            if ((t.contains("shaam") || t.contains("शाम") || t.contains("raat") || t.contains("रात") || t.contains("evening") || t.contains("night")) && hour < 12) hour += 12
            cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val tomorrow = t.contains("tomorrow") || t.contains(" kal ") || t.contains("कल")
            if (tomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
            else if (cal.timeInMillis <= nowMs) {
                if (suffix !in listOf("am", "pm") && hour < 12) cal.add(Calendar.HOUR_OF_DAY, 12)
                if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis to true
        }
        return nowMs + 30 * 60_000 to false
    }
}
