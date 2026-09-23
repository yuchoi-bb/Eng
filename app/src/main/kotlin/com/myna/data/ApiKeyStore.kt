package com.myna.data

import android.content.Context

/**
 * Gemini API 키 보관.
 *
 * REQUIREMENTS §9.2는 "API 키를 APK에 넣지 않는다"며 Cloud Run 프록시를 세우기로 했다.
 * 그 결정의 근거는 **디컴파일**이다 — APK에 박힌 키는 누구나 꺼낼 수 있다.
 *
 * 여기서는 키를 APK에 넣지 않는다. 사용자가 설정 화면에서 직접 입력하고, 자기 기기의
 * 앱 전용 저장소에만 남는다. 디컴파일로는 나오지 않으므로 §9.2가 막으려던 위험은 없다.
 * 서버를 세우지 않아도 오늘 동작한다는 것이 이 선택의 값이다.
 *
 * **다만 하드웨어로 보호되지는 않는다.** 루팅된 기기나 물리적으로 추출당한 기기에서는
 * 읽힐 수 있다. 본인 키이고 언제든 폐기할 수 있으므로 개인용으로는 감수할 만하지만,
 * 여러 사람이 쓰게 되는 순간 프록시(§9.2)로 옮겨야 한다.
 */
internal class ApiKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var geminiKey: String?
        get() = prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_GEMINI) else putString(KEY_GEMINI, value.trim())
            }.apply()
        }

    /** 설정에서 직접 지정한 모델. 비어 있으면 앱이 목록에서 고른다. */
    var modelOverride: String?
        get() = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_MODEL) else putString(KEY_MODEL, value.trim())
            }.apply()
        }

    /** 실제로 영상 전사에 성공한 모델. 매번 후보를 훑지 않기 위해 기억한다. */
    var resolvedModel: String?
        get() = prefs.getString(KEY_RESOLVED, null)
        set(value) {
            prefs.edit().putString(KEY_RESOLVED, value).apply()
        }

    /**
     * 유튜브 영상 입력을 거절한 모델들.
     *
     * `models.list`는 어떤 모델이 영상을 받는지 알려 주지 않는다. 한 번 거절당한 모델을
     * 기억해 두지 않으면 전사할 때마다 같은 모델에 같은 요청을 보내고 같은 거절을 받는다 —
     * 한 번의 왕복이 수십 초다.
     */
    var unsupportedModels: Set<String>
        get() = prefs.getStringSet(KEY_UNSUPPORTED, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_UNSUPPORTED, value).apply()
        }

    fun markUnsupported(model: String) {
        unsupportedModels = unsupportedModels + model
        if (resolvedModel == model) resolvedModel = null
    }

    /** 키를 바꾸면 모델 판단을 처음부터 다시 한다. 키마다 쓸 수 있는 모델이 다르다. */
    fun forgetModelDiscovery() {
        prefs.edit().remove(KEY_RESOLVED).remove(KEY_UNSUPPORTED).apply()
    }

    val hasKey: Boolean get() = geminiKey != null

    private companion object {
        // 학습 기록(app-state.json)과 섞지 않는다. 그쪽은 나중에 동기화 대상이 된다.
        const val FILE = "myna-secrets"
        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_MODEL = "gemini_model_override"
        const val KEY_RESOLVED = "gemini_model_resolved"
        const val KEY_UNSUPPORTED = "gemini_models_unsupported"
    }
}
