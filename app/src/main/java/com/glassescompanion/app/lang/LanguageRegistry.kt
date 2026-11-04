package com.glassescompanion.app.lang

import java.util.Locale

data class Language(
    /** Stable registry id (BCP-47). */
    val code: String,
    val name: String,
    val native: String,
    /** Tag handed to SpeechRecognizer / TextToSpeech when it differs from [code]. */
    val speechTag: String = code,
    val rtl: Boolean = false,
) {
    val locale: Locale get() = Locale.forLanguageTag(speechTag)
    val label: String get() = if (native == name) name else "$name · $native"
}

/**
 * Exactly 100 distinct languages. Regional variants are never counted as
 * separate languages (Chinese is a single entry: Mainland Mandarin, Simplified).
 */
object LanguageRegistry {

    val MANDATORY = listOf("en", "hi", "es", "ur", "ta", "zh-CN", "bn")

    val all: List<Language> = listOf(
        Language("en", "English", "English", "en-US"),
        Language("hi", "Hindi", "हिन्दी", "hi-IN"),
        Language("es", "Spanish", "Español", "es-ES"),
        Language("ur", "Urdu", "اردو", "ur-PK", rtl = true),
        Language("ta", "Tamil", "தமிழ்", "ta-IN"),
        Language("zh-CN", "Chinese (Mandarin, Simplified)", "简体中文", "zh-CN"),
        Language("bn", "Bengali", "বাংলা", "bn-IN"),
        Language("ar", "Arabic", "العربية", "ar-SA", rtl = true),
        Language("fr", "French", "Français", "fr-FR"),
        Language("de", "German", "Deutsch", "de-DE"),
        Language("pt", "Portuguese", "Português", "pt-BR"),
        Language("ru", "Russian", "Русский", "ru-RU"),
        Language("ja", "Japanese", "日本語", "ja-JP"),
        Language("ko", "Korean", "한국어", "ko-KR"),
        Language("it", "Italian", "Italiano", "it-IT"),
        Language("tr", "Turkish", "Türkçe", "tr-TR"),
        Language("vi", "Vietnamese", "Tiếng Việt", "vi-VN"),
        Language("th", "Thai", "ไทย", "th-TH"),
        Language("id", "Indonesian", "Bahasa Indonesia", "id-ID"),
        Language("ms", "Malay", "Bahasa Melayu", "ms-MY"),
        Language("fa", "Persian", "فارسی", "fa-IR", rtl = true),
        Language("pl", "Polish", "Polski", "pl-PL"),
        Language("uk", "Ukrainian", "Українська", "uk-UA"),
        Language("nl", "Dutch", "Nederlands", "nl-NL"),
        Language("sv", "Swedish", "Svenska", "sv-SE"),
        Language("no", "Norwegian", "Norsk", "nb-NO"),
        Language("da", "Danish", "Dansk", "da-DK"),
        Language("fi", "Finnish", "Suomi", "fi-FI"),
        Language("el", "Greek", "Ελληνικά", "el-GR"),
        Language("he", "Hebrew", "עברית", "he-IL", rtl = true),
        Language("cs", "Czech", "Čeština", "cs-CZ"),
        Language("sk", "Slovak", "Slovenčina", "sk-SK"),
        Language("hu", "Hungarian", "Magyar", "hu-HU"),
        Language("ro", "Romanian", "Română", "ro-RO"),
        Language("bg", "Bulgarian", "Български", "bg-BG"),
        Language("hr", "Croatian", "Hrvatski", "hr-HR"),
        Language("sr", "Serbian", "Српски", "sr-RS"),
        Language("sl", "Slovenian", "Slovenščina", "sl-SI"),
        Language("lt", "Lithuanian", "Lietuvių", "lt-LT"),
        Language("lv", "Latvian", "Latviešu", "lv-LV"),
        Language("et", "Estonian", "Eesti", "et-EE"),
        Language("mr", "Marathi", "मराठी", "mr-IN"),
        Language("te", "Telugu", "తెలుగు", "te-IN"),
        Language("kn", "Kannada", "ಕನ್ನಡ", "kn-IN"),
        Language("ml", "Malayalam", "മലയാളം", "ml-IN"),
        Language("gu", "Gujarati", "ગુજરાતી", "gu-IN"),
        Language("pa", "Punjabi", "ਪੰਜਾਬੀ", "pa-IN"),
        Language("or", "Odia", "ଓଡ଼ିଆ", "or-IN"),
        Language("as", "Assamese", "অসমীয়া", "as-IN"),
        Language("ne", "Nepali", "नेपाली", "ne-NP"),
        Language("si", "Sinhala", "සිංහල", "si-LK"),
        Language("my", "Burmese", "မြန်မာ", "my-MM"),
        Language("km", "Khmer", "ខ្មែរ", "km-KH"),
        Language("lo", "Lao", "ລາວ", "lo-LA"),
        Language("tl", "Filipino", "Filipino", "fil-PH"),
        Language("sw", "Swahili", "Kiswahili", "sw-KE"),
        Language("am", "Amharic", "አማርኛ", "am-ET"),
        Language("yo", "Yoruba", "Yorùbá", "yo-NG"),
        Language("ig", "Igbo", "Asụsụ Igbo", "ig-NG"),
        Language("ha", "Hausa", "Harshen Hausa", "ha-NG"),
        Language("zu", "Zulu", "isiZulu", "zu-ZA"),
        Language("xh", "Xhosa", "isiXhosa", "xh-ZA"),
        Language("af", "Afrikaans", "Afrikaans", "af-ZA"),
        Language("so", "Somali", "Soomaali", "so-SO"),
        Language("ca", "Catalan", "Català", "ca-ES"),
        Language("eu", "Basque", "Euskara", "eu-ES"),
        Language("gl", "Galician", "Galego", "gl-ES"),
        Language("cy", "Welsh", "Cymraeg", "cy-GB"),
        Language("ga", "Irish", "Gaeilge", "ga-IE"),
        Language("is", "Icelandic", "Íslenska", "is-IS"),
        Language("mt", "Maltese", "Malti", "mt-MT"),
        Language("sq", "Albanian", "Shqip", "sq-AL"),
        Language("mk", "Macedonian", "Македонски", "mk-MK"),
        Language("bs", "Bosnian", "Bosanski", "bs-BA"),
        Language("be", "Belarusian", "Беларуская", "be-BY"),
        Language("ka", "Georgian", "ქართული", "ka-GE"),
        Language("hy", "Armenian", "Հայերեն", "hy-AM"),
        Language("az", "Azerbaijani", "Azərbaycanca", "az-AZ"),
        Language("kk", "Kazakh", "Қазақ тілі", "kk-KZ"),
        Language("uz", "Uzbek", "Oʻzbekcha", "uz-UZ"),
        Language("ky", "Kyrgyz", "Кыргызча", "ky-KG"),
        Language("tg", "Tajik", "Тоҷикӣ", "tg-TJ"),
        Language("mn", "Mongolian", "Монгол", "mn-MN"),
        Language("ps", "Pashto", "پښتو", "ps-AF", rtl = true),
        Language("sd", "Sindhi", "سنڌي", "sd-PK", rtl = true),
        Language("ku", "Kurdish (Kurmanji)", "Kurdî", "ku-TR"),
        Language("lb", "Luxembourgish", "Lëtzebuergesch", "lb-LU"),
        Language("eo", "Esperanto", "Esperanto", "eo"),
        Language("jv", "Javanese", "Basa Jawa", "jv-ID"),
        Language("su", "Sundanese", "Basa Sunda", "su-ID"),
        Language("ceb", "Cebuano", "Sinugbuanong Binisayâ", "ceb-PH"),
        Language("haw", "Hawaiian", "ʻŌlelo Hawaiʻi", "haw-US"),
        Language("mi", "Maori", "Te Reo Māori", "mi-NZ"),
        Language("sm", "Samoan", "Gagana Sāmoa", "sm-WS"),
        Language("mg", "Malagasy", "Malagasy", "mg-MG"),
        Language("ny", "Chichewa", "Chichewa", "ny-MW"),
        Language("rw", "Kinyarwanda", "Ikinyarwanda", "rw-RW"),
        Language("st", "Sesotho", "Sesotho", "st-ZA"),
        Language("sn", "Shona", "chiShona", "sn-ZW"),
        Language("yi", "Yiddish", "ייִדיש", "yi", rtl = true),
    )

    private val byCode = all.associateBy { it.code.lowercase() }

    fun find(code: String?): Language? = code?.let { byCode[it.lowercase()] }
    fun require(code: String?): Language = find(code) ?: all.first()

    /** Base language subtag used by ML Kit (e.g. "zh-CN" -> "zh"). */
    fun mlKitTag(lang: Language): String = when (lang.code) {
        "zh-CN" -> "zh"
        else -> lang.code
    }
}
