package com.github.serezhka.airplay.app.i18n;

import com.github.serezhka.airplay.app.settings.AppSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class I18nTest {

    @Test
    void switchesBetweenEnglishAndChinese() {
        I18n i18n = new I18n(AppSettings.LanguageMode.EN);
        assertThat(i18n.text("home.title")).isEqualTo("Receiver");

        i18n.setLanguage(AppSettings.LanguageMode.ZH_CN);
        assertThat(i18n.text("home.title")).isEqualTo("接收投屏");
    }
}
