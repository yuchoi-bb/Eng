package com.eng.shadowing

import android.app.Application
import com.eng.shadowing.data.LocalStore
import com.eng.shadowing.data.ShadowingRepository

/**
 * S0에는 DI 프레임워크를 넣지 않는다. 의존이 저장소 하나뿐이라 프레임워크가 벌어 주는 것이 없다.
 * S1에서 프록시 클라이언트와 Firestore가 들어올 때 다시 판단한다.
 */
public class EngApplication : Application() {

    public val repository: ShadowingRepository by lazy {
        ShadowingRepository(LocalStore(filesDir))
    }
}
