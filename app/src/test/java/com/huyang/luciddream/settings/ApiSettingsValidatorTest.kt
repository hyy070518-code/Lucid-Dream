package com.huyang.luciddream.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiSettingsValidatorTest {
    @Test
    fun officialDeepSeekUrlIsAccepted() {
        assertNull(ApiSettingsValidator.validateBaseUrl("https://api.deepseek.com"))
    }

    @Test
    fun cleartextUrlIsRejected() {
        assertEquals(
            "Base URL 必须使用 HTTPS",
            ApiSettingsValidator.validateBaseUrl("http://api.deepseek.com"),
        )
    }

    @Test
    fun blankModelIsRejected() {
        assertEquals("Model 不能为空", ApiSettingsValidator.validateModel("  "))
    }
}
